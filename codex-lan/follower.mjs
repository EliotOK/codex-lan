import net from 'node:net';
import { randomUUID } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { encodeFrame } from './bridge.mjs';
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';

// Desktop's follower channel keeps the desktop window as the conversation owner.
export class DesktopFollower extends EventEmitter {
  constructor({pipePath='\\\\.\\pipe\\codex-ipc',queuePath=path.join(process.env.CODEX_HOME??path.join(process.env.USERPROFILE??'', '.codex'),'.codex-global-state.json')}={}) {
    super();this.pipePath=pipePath;this.pending=new Map();this.snapshots=new Map();this.owners=new Map();this.queues=new Map();this.flights=new Map();
    this.queuePath=queuePath;this.touched=new Map();
    this.cleanup=setInterval(()=>{for(const [id,time]of this.touched)if(Date.now()-time>300000)this.unfollow(id);},60000);this.cleanup.unref();
  }
  async connect() {
    if(this.socket&&!this.socket.destroyed&&this.clientId)return;
    if(this.connecting)return this.connecting;
    this.connecting=(async()=>{
      const socket=net.createConnection(this.pipePath);this.socket=socket;this.buffer=Buffer.alloc(0);
      socket.on('data',bytes=>{if(this.socket===socket)this.receive(bytes);});
      socket.on('error',()=>this.reset(socket));socket.on('close',()=>this.reset(socket));
      await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{socket.destroy();reject(new Error('桌面跟随连接超时'));},3000);socket.once('connect',()=>{clearTimeout(timer);resolve();});socket.once('error',error=>{clearTimeout(timer);reject(error);});});
      this.clientId=(await this.request('initialize',{clientType:'codex-light'},0)).result.clientId;
    })().finally(()=>{this.connecting=null;});return this.connecting;
  }
  reset(socket) {
    if(this.socket!==socket)return;
    this.socket=null;this.clientId=null;this.snapshots.clear();this.owners.clear();this.queues.clear();
    for(const value of this.pending.values()){clearTimeout(value.timer);value.reject(new Error('桌面跟随连接已断开'));}this.pending.clear();
    socket.destroy();
  }
  close(){clearInterval(this.cleanup);if(this.socket)this.reset(this.socket);}
  unfollow(id){const owner=this.owners.get(id);if(owner&&this.clientId&&this.socket&&!this.socket.destroyed)this.send({type:'broadcast',method:'thread-stream-following-changed',sourceClientId:this.clientId,version:1,targetClientIds:[owner],params:{hostId:'local',conversationId:id,following:false}});this.touched.delete(id);this.owners.delete(id);this.snapshots.delete(id);this.queues.delete(id);}
  async readQueued(threadId){
    if(!this.owners.has(threadId))throw new Error('桌面会话尚未连接');
    try{const info=await stat(this.queuePath);if(info.size>16*1024*1024)throw new Error('排队消息记录过大');const state=JSON.parse(await readFile(this.queuePath,'utf8'));const messages=state['queued-follow-ups']?.[threadId]??[];if(!Array.isArray(messages))throw new Error('排队消息格式无效');return messages;}
    catch(error){if(this.queues.has(threadId))return this.queues.get(threadId);if(error.code==='ENOENT')return [];throw error;}
  }
  send(message){if(!this.socket||this.socket.destroyed)throw new Error('桌面跟随未连接');this.socket.write(encodeFrame(message));}
  request(method,params,version=1,targetClientId) {
    const requestId=randomUUID();return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(requestId);reject(new Error('桌面跟随请求超时；请刷新核对结果'));},5000);
      this.pending.set(requestId,{resolve,reject,timer});
      try{this.send({type:'request',requestId,sourceClientId:this.clientId??'initializing-client',version,method,params,targetClientId,timeoutMs:4500});}
      catch(error){clearTimeout(timer);this.pending.delete(requestId);reject(error);}
    });
  }
  receive(bytes) {
    this.buffer=Buffer.concat([this.buffer,bytes]);
    while(this.buffer.length>=4){const size=this.buffer.readUInt32LE(0);if(!size||size>32*1024*1024){this.close();return;}if(this.buffer.length<size+4)return;
      let message;try{message=JSON.parse(this.buffer.subarray(4,size+4));}catch{this.close();return;}this.buffer=this.buffer.subarray(size+4);this.handle(message);
    }
  }
  handle(message) {
    if(message.type==='response') {
      const pending=this.pending.get(message.requestId);if(!pending)return;
      this.pending.delete(message.requestId);clearTimeout(pending.timer);
      if(message.resultType==='success')pending.resolve(message);else pending.reject(new Error(message.error??'桌面请求失败'));
    } else if(message.type==='client-discovery-request') {
      this.send({type:'client-discovery-response',requestId:message.requestId,response:{canHandle:false}});
    } else if(message.type==='broadcast'){
      const p=message.params??{},id=p.conversationId;
      if(p.hostId==='local'&&this.owners.get(id)===message.sourceClientId&&message.method==='thread-stream-state-changed'&&message.version===11){
        const change=p.change,prior=this.snapshots.get(id);
        if(change?.type==='snapshot')this.snapshots.set(id,{revision:change.revision,state:change.conversationState});
        else if(change?.type==='patches'&&prior?.revision===change.baseRevision){
          try{this.snapshots.set(id,{revision:change.revision,state:applyPatches(prior.state,change.patches)});}catch{this.snapshots.delete(id);}
        }else this.snapshots.delete(id);
      }
      if(p.hostId==='local'&&this.owners.get(id)===message.sourceClientId&&message.method==='thread-queued-followups-changed'&&message.version===2)this.queues.set(id,p.messages??[]);
      if(message.method==='client-status-changed'&&p.status==='disconnected')for(const [thread,owner]of this.owners)if(owner===p.clientId){this.snapshots.delete(thread);this.owners.delete(thread);this.queues.delete(thread);}
      this.emit('broadcast',message);
    }
  }
  async follow(threadId) {
    await this.connect();
    const owner=await this.request('thread-owner-discovery',{hostId:'local',conversationId:threadId});
    this.owners.set(threadId,owner.handledByClientId);
    this.send({type:'broadcast',method:'thread-stream-following-changed',sourceClientId:this.clientId,version:1,targetClientIds:[owner.handledByClientId],params:{hostId:'local',conversationId:threadId,following:true}});
    return owner.handledByClientId;
  }
  async snapshot(threadId) {
    await this.connect();
    this.touched.delete(threadId);this.touched.set(threadId,Date.now());
    while(this.touched.size>8)this.unfollow(this.touched.keys().next().value);
    if(this.snapshots.has(threadId))return this.snapshots.get(threadId).state;
    if(this.flights.has(threadId))return this.flights.get(threadId);
    const flight=(async()=>{
      if(this.owners.has(threadId))this.send({type:'broadcast',method:'thread-stream-following-changed',sourceClientId:this.clientId,version:1,targetClientIds:[this.owners.get(threadId)],params:{hostId:'local',conversationId:threadId,following:false}});
      await this.follow(threadId);
      const started=Date.now();while(Date.now()-started<2500){if(this.snapshots.has(threadId))return this.snapshots.get(threadId).state;await new Promise(resolve=>setTimeout(resolve,25));}
      throw new Error('桌面实时状态暂不可用');
    })().finally(()=>this.flights.delete(threadId));this.flights.set(threadId,flight);return flight;
  }
  async submit(threadId,method,params,version=1){await this.snapshot(threadId);return (await this.request(method,{conversationId:threadId,...params},version,this.owners.get(threadId))).result;}
}

export function applyPatches(state,patches){
  let result=structuredClone(state);
  for(const patch of patches??[]){const keys=patch.path;if(!Array.isArray(keys)||keys.some(key=>['__proto__','prototype','constructor'].includes(key)))throw new Error('无效的状态更新');
    if(!keys.length){if(patch.op==='remove')throw new Error('状态已移除');result=structuredClone(patch.value);continue;}
    let parent=result;for(const key of keys.slice(0,-1)){if(parent==null||!Object.hasOwn(parent,key))throw new Error('状态更新缺少路径');parent=parent[key];}
    const key=keys.at(-1);if(patch.op==='remove'){if(Array.isArray(parent))parent.splice(Number(key),1);else delete parent[key];}
    else if(patch.op==='add'&&Array.isArray(parent))parent.splice(Number(key),0,structuredClone(patch.value));
    else if(patch.op==='add'||patch.op==='replace')parent[key]=structuredClone(patch.value);else throw new Error('不支持的状态更新');
  }return result;
}
