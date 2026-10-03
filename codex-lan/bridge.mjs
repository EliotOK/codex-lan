import net from 'node:net';
import { readdir, open } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { modelCatalog } from './models.mjs';

const MAX_FRAME = 8 * 1024 * 1024;
export function encodeFrame(message) {
  const body = Buffer.from(JSON.stringify(message));
  if (body.length > MAX_FRAME) throw new Error('请求过大');
  const result = Buffer.alloc(4 + body.length);
  result.writeUInt32LE(body.length); body.copy(result, 4);
  return result;
}

export class DesktopBridge {
  constructor({ pipePath, autoPipePath, callerThreadId, logRoot } = {}) {
    this.pipePath = pipePath;
    this.autoPipePath = autoPipePath;
    this.callerThreadId = callerThreadId;
    this.logRoot = logRoot ?? path.join(process.env.LOCALAPPDATA ?? '', 'Codex', 'Logs');
    this.pending = new Map(); this.nextId = 1; this.buffer = Buffer.alloc(0);
  }
  async discover() {
    if (this.pipePath) return this.pipePath;
    if (this.autoPipePath) return this.autoPipePath;
    const livePipes=process.platform==='win32'?await readdir('\\\\.\\pipe\\').catch(()=>null):null;
    // The desktop emits its endpoint at startup. Inspect recent endpoint records only.
    const files = [];
    const walk = async (directory, depth) => {
      let entries;try{entries=await readdir(directory,{withFileTypes:true});}catch(error){if(directory===this.logRoot&&!livePipes)throw new Error(`无法读取 Desktop 日志目录（${error.code}），请检查守护任务用户权限`);return;}
      for (const entry of entries) {
        const full = path.join(directory, entry.name);
        if (entry.isDirectory() && depth > 0) await walk(full, depth - 1);
        else if (entry.isFile() && entry.name.endsWith('.log')) {const h=await open(full,'r').catch(()=>null);if(h){try{files.push({full,mtime:(await h.stat()).mtimeMs});}finally{await h.close();}}}
      }
    };
    await walk(this.logRoot, 4);
    for (const file of files.sort((a,b)=>b.mtime-a.mtime).slice(0, 96)) {
      const handle = await open(file.full, 'r').catch(()=>null);
      if(!handle)continue;
      try {
        const size = (await handle.stat()).size;
        const bytes = Buffer.alloc(Math.min(size, 512 * 1024));
        await handle.read(bytes, 0, bytes.length, 0);
        let text=bytes.toString('utf8');
        if(size>bytes.length){await handle.read(bytes,0,bytes.length,size-bytes.length);text+='\n'+bytes.toString('utf8');}
        const hits = [...text.matchAll(/dynamic_app_tools_listening[^\r\n]*pipePath=(\\\\\.\\pipe\\[^\s\r\n]+)/g)];
        for(const hit of hits.reverse())if(!livePipes||livePipes.includes(hit[1].slice('\\\\.\\pipe\\'.length)))return hit[1];
      } finally { await handle.close(); }
    }
    // Rotated or inaccessible startup logs can omit the endpoint. Identify the
    // app-tools pipe with a read-only capability request before using it.
    for(const name of (livePipes??[]).filter(name=>/^codex-browser-use-[a-f0-9-]+$/i.test(name)).slice(0,12)){
      const endpoint='\\\\.\\pipe\\'+name;
      if(await this.isAppToolsPipe(endpoint))return endpoint;
    }
    throw new Error('未找到桌面连接。请打开 Codex Desktop 后重试。');
  }
  isAppToolsPipe(endpoint){return new Promise(resolve=>{
    const socket=net.createConnection(endpoint);let bytes=Buffer.alloc(0),settled=false;
    const finish=value=>{if(settled)return;settled=true;clearTimeout(timer);socket.destroy();resolve(value);};
    const timer=setTimeout(()=>finish(false),1500);
    socket.on('error',()=>finish(false));socket.on('close',()=>finish(false));
    socket.on('connect',()=>socket.write(encodeFrame({id:0,jsonrpc:'2.0',method:'tools/list',params:{threadStartKind:'all'}})));
    socket.on('data',chunk=>{bytes=Buffer.concat([bytes,chunk]);if(bytes.length<4)return;const size=bytes.readUInt32LE(0);if(size>MAX_FRAME)return finish(false);if(bytes.length<size+4)return;try{const response=JSON.parse(bytes.subarray(4,size+4));finish(response.result?.tools?.some(tool=>tool.namespace==='codex_app'&&tool.name==='list_threads')===true);}catch{finish(false);}});
  });}
  async connect() {
    if (this.socket && !this.socket.destroyed) return;
    if (this.connecting) return this.connecting;
    this.connecting = (async () => {
      const endpoint = await this.discover();
      await new Promise((resolve, reject) => {
        const socket = net.createConnection(endpoint);
        const timeout = setTimeout(() => socket.destroy(new Error('桌面连接超时')), 5000);
        socket.once('error', reject);
        socket.once('error',()=>{this.autoPipePath=null;});
        socket.once('connect', () => {
          clearTimeout(timeout); socket.off('error', reject);
          this.socket = socket; this.buffer = Buffer.alloc(0);
          socket.on('data', data => { if (this.socket === socket) this.onData(data); });
          socket.on('error', error => { if (this.socket === socket) this.disconnect(error); });
          socket.on('close', () => { if (this.socket === socket) this.disconnect(new Error('桌面连接已关闭，请重试')); });
          resolve();
        });
        socket.once('close', () => clearTimeout(timeout));
      });
    })().finally(() => { this.connecting = null; });
    return this.connecting;
  }
  onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 4) {
      const size = this.buffer.readUInt32LE(0);
      if (size > MAX_FRAME) { this.close(); return; }
      if (this.buffer.length < size + 4) return;
      const body = this.buffer.subarray(4, size + 4); this.buffer = this.buffer.subarray(size + 4);
      let message;
      try { message = JSON.parse(body); } catch { this.close(); return; }
      const pending = this.pending.get(message.id);
      if (!pending) continue;
      this.pending.delete(message.id); clearTimeout(pending.timer);
      if (message.error) pending.reject(new Error(message.error.message));
      else pending.resolve(message.result);
    }
  }
  disconnect(error) {
    const socket = this.socket;
    this.socket = null; this.buffer = Buffer.alloc(0);
    this.autoPipePath = null;
    socket?.destroy();
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
    this.pending.clear();
  }
  close() { this.socket?.destroy(); this.disconnect(new Error('桌面连接已断开')); }
  async request(method, params, timeoutMs = 15000) {
    await this.connect();
    const socket = this.socket;
    if (!socket || socket.destroyed) throw new Error('桌面连接已关闭，正在重新连接');
    const id = this.nextId++;
    const frame = encodeFrame({ id, jsonrpc: '2.0', method, params });
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.socket === socket) this.disconnect(new Error('桌面请求超时，连接将重新建立；发送结果可能未知，请查看聊天后再操作'));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      socket.write(frame, error => {
        if (error && this.socket === socket) this.disconnect(error);
      });
    });
  }
  async check() {
    const result = await this.request('tools/list', { threadStartKind: 'all' });
    const names = new Set(result.tools?.filter(t => t.namespace === 'codex_app').map(t => t.name));
    for (const name of ['list_threads', 'read_thread', 'send_message_to_thread']) {
      if (!names.has(name)) throw new Error(`桌面版本缺少接口：${name}`);
    }
    return { connected: true, transport: 'desktop-native-pipe', approvals: false, tokenStreaming: false };
  }
  async call(tool, args) {
    if (!['list_threads', 'read_thread', 'send_message_to_thread', 'get_usage_limits', 'list_projects', 'navigate_to_codex_page'].includes(tool)) throw new Error('不支持的操作');
    if (!this.callerThreadId) throw new Error('缺少连接来源聊天 ID，请从连接设置填写');
    const result = await this.request('tools/call', {
      namespace: 'codex_app', tool, arguments: args, callerSource: 'codex',
      threadId: this.callerThreadId, turnId: `lan-${randomUUID()}`, callId: `lan-${randomUUID()}`,
    }, tool === 'send_message_to_thread' ? 60000 : 15000);
    const text = result.contentItems?.filter(item => item.type === 'inputText').map(item => item.text).join('\n') ?? '';
    if (!result.success) throw new Error(text || '桌面操作失败');
    try { return JSON.parse(text); } catch { return { text }; }
  }
  list() { return this.call('list_threads', { limit: 50 }); }
  projects() { return this.call('list_projects', {}); }
  async models() {
    const result = await this.request('tools/list', { threadStartKind: 'all' });
    return modelCatalog(result.tools?.find(tool => tool.namespace === 'codex_app' && tool.name === 'send_message_to_thread'));
  }
  usage() { return this.call('get_usage_limits', {}); }
  read(threadId, cursor) {
    return this.call('read_thread', { threadId, hostId: 'local', turnLimit: 5, includeOutputs: true, maxOutputCharsPerItem: 20000, ...(cursor ? { cursor } : {}) });
  }
  openDesktop(threadId) { return this.call('navigate_to_codex_page',{threadId}); }
  send(threadId, prompt, options = {}) { return this.call('send_message_to_thread', { threadId, hostId: 'local', prompt, ...options }); }
}
