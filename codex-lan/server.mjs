import http from 'node:http';
import https from 'node:https';
import { readFile, writeFile, mkdir, rename } from 'node:fs/promises';
import { randomBytes, randomInt, timingSafeEqual, createHash } from 'node:crypto';
import { networkInterfaces } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DesktopBridge } from './bridge.mjs';
import { ImageRegistry } from './images.mjs';
import { normalizeUsage } from './usage.mjs';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const UUID = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i;
const json = (response, status, body) => { response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); response.end(JSON.stringify(body)); };
const equal = (a, b) => { const x=Buffer.from(a), y=Buffer.from(b); return x.length === y.length && timingSafeEqual(x,y); };
const local = request => ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(request.socket.remoteAddress);
export function addresses() {
  return Object.values(networkInterfaces()).flat().filter(i => i && !i.internal && i.family === 'IPv4' && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(i.address)).map(i => i.address);
}
async function readBody(request) {
  let bytes=0; const parts=[];
  for await (const part of request) {
    bytes+=part.length; if(bytes>65536) throw Object.assign(new Error('请求过大'),{status:413}); parts.push(part);
  }
  try { return JSON.parse(Buffer.concat(parts).toString('utf8')); }
  catch { throw Object.assign(new Error('请求格式错误'),{status:400}); }
}
export function createApp({ bridge, pairingCode=String(randomInt(10000000,100000000)), lanAddresses=addresses(), port=8787, receiptPath, certificate, initialReceipts=[], initialSessions=[], saveSessions=async()=>{}, savePairingCode=async()=>{}, saveConnectionId=async()=>{} } = {}) {
  const sessions=new Map(initialSessions.filter(([hash,s])=>/^[a-f0-9]{64}$/.test(hash)&&s.expires>Date.now()&&typeof s.csrf==='string')), attempts=new Map(), allowedThreads=new Set(), cache=new Map();
  const images=new ImageRegistry();
  let usageCache, usageFlight;
  const hashToken=token=>createHash('sha256').update(token??'').digest('hex');
  let sessionFlight=Promise.resolve(), settingsFlight=Promise.resolve();
  const persistSessions=()=>{const snapshot=[...sessions];sessionFlight=sessionFlight.catch(()=>{}).then(()=>saveSessions(snapshot));return sessionFlight;};
  const receipts=new Map(initialReceipts.map(r=>[r.id,r])); let listFlight, saveFlight=Promise.resolve();
  const cookieName='codex_lan';
  const prune=()=>{
    const now=Date.now();
    for(const [k,v] of sessions) if(v.expires<now)sessions.delete(k);
    for(const [k,v] of attempts) if(v.until<now)attempts.delete(k);
  };
  const persist=()=>{if(receiptPath) saveFlight=saveFlight.catch(()=>{}).then(()=>writeFile(receiptPath,JSON.stringify([...receipts.values()]),{mode:0o600})); return saveFlight;};
  async function list() {
    if(listFlight)return listFlight;
    listFlight=bridge.list().then(result=>{
      const threads=[...(result.pinnedThreads??[]),...(result.threads??[])].filter(t=>t.kind==='codex' && (t.hostId??'local')==='local');
      allowedThreads.clear(); threads.forEach(t=>allowedThreads.add(t.id));
      return {threads};
    }).finally(()=>{listFlight=null});
    return listFlight;
  }
  const handler=async(request,response)=>{
    response.setHeader('Cache-Control','no-store'); response.setHeader('X-Content-Type-Options','nosniff');
    response.setHeader('Referrer-Policy','no-referrer'); response.setHeader('X-Frame-Options','DENY');
    response.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    try {
      const host=request.headers.host??'';
      let origin; try { origin=new URL(`${request.socket.encrypted?'https':'http'}://${host}`); } catch { return json(response,400,{error:'地址无效'}); }
      if(!['localhost','127.0.0.1','[::1]',...lanAddresses].includes(origin.hostname))return json(response,403,{error:'不允许此地址'});
      if(request.headers.origin && request.headers.origin!==origin.origin)return json(response,403,{error:'不允许跨站请求'});
      if(request.headers['sec-fetch-site']==='cross-site')return json(response,403,{error:'不允许跨站请求'});
      if(!request.socket.encrypted&&!local(request))return json(response,403,{error:'局域网连接需要 HTTPS'});
      const url=new URL(request.url,origin), route=url.pathname;
      if(request.method==='GET' && route==='/api/local-info') {
        if(!local(request))return json(response,403,{error:'请在电脑查看连接信息'});
        return json(response,200,{pairingCode,urls:lanAddresses.map(ip=>`https://${ip}:${port}`),callerThreadId:bridge.callerThreadId??'',approvals:false});
      }
      if(request.method==='GET' && route==='/cert.pem' && certificate) {
        response.writeHead(200,{'Content-Type':'application/x-pem-file','Content-Disposition':'attachment; filename="codex-lan.pem"'}); return response.end(certificate);
      }
      const files={'/':'index.html','/app.js':'app.js','/style.css':'style.css','/icon.svg':'icon.svg','/manifest.webmanifest':'manifest.webmanifest'};
      if(request.method==='GET'&&files[route]) {
        const mime=route.endsWith('.js')?'text/javascript':route.endsWith('.css')?'text/css':route.endsWith('.svg')?'image/svg+xml':route.endsWith('.webmanifest')?'application/manifest+json':'text/html';
        response.writeHead(200,{'Content-Type':`${mime}; charset=utf-8`});return response.end(await readFile(path.join(ROOT,'public',files[route])));
      }
      prune();
      if(request.method==='POST' && route==='/api/pair') {
        const key=request.socket.remoteAddress, a=attempts.get(key)??{count:0,until:Date.now()+60000};
        if(a.count>=5)return json(response,429,{error:'尝试过多，请一分钟后再试'});
        const body=await readBody(request);
        if(!equal(String(body.code??''),pairingCode)){a.count++;attempts.set(key,a);return json(response,401,{error:'配对码不正确'});}
        attempts.delete(key);
        if(sessions.size>=32)return json(response,429,{error:'连接数量已达到上限'});
        const token=randomBytes(32).toString('hex'),csrf=randomBytes(24).toString('hex');
        const hash=hashToken(token);
        sessions.set(hash,{csrf,expires:Date.now()+12*60*60*1000});
        try{await persistSessions();}catch(error){sessions.delete(hash);throw error;}
        response.setHeader('Set-Cookie',`${cookieName}=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200${request.socket.encrypted?'; Secure':''}`);
        return json(response,200,{csrf});
      }
      const token=(request.headers.cookie??'').split(';').map(x=>x.trim()).find(x=>x.startsWith(`${cookieName}=`))?.slice(cookieName.length+1);
      const session=sessions.get(hashToken(token));
      if(!session)return json(response,401,{error:'请先配对'});
      if(request.method==='POST'&&!equal(String(request.headers['x-csrf-token']??''),session.csrf))return json(response,403,{error:'请求验证失败'});
      if(route==='/api/session'&&request.method==='GET')return json(response,200,{csrf:session.csrf});
      if(route==='/api/logout'&&request.method==='POST') {sessions.delete(hashToken(token));await persistSessions();return json(response,200,{ok:true});}
      if(route==='/api/settings/pairing-code'&&request.method==='POST') {
        if(!local(request))return json(response,403,{error:'请在电脑连接面板修改配对码'});
        const body=await readBody(request),next=body.random===true?String(randomInt(10000000,100000000)):String(body.code??'');
        if(!/^[0-9]{8}$/.test(next))return json(response,400,{error:'配对码必须是 8 位数字'});
        settingsFlight=settingsFlight.catch(()=>{}).then(async()=>{await savePairingCode(next);pairingCode=next;attempts.clear();});
        await settingsFlight;return json(response,200,{pairingCode,existingDevicesRemainPaired:true});
      }
      if(route==='/api/status'&&request.method==='GET')return json(response,200,await bridge.check());
      if(route==='/api/usage'&&request.method==='GET'){
        const age=usageCache?Date.now()-usageCache.fetchedAt:Infinity;
        if(age>(url.searchParams.get('refresh')==='1'?5000:60000)){
          if(!usageFlight)usageFlight=bridge.usage().then(normalizeUsage).then(value=>{usageCache=value;return value;}).finally(()=>{usageFlight=null});
          await usageFlight;
        }
        return json(response,200,usageCache);
      }
      if(route==='/api/threads'&&request.method==='GET')return json(response,200,await list());
      const imageRoute=route.match(/^\/api\/images\/([a-f0-9]{64})$/);
      if(imageRoute&&request.method==='GET'){
        const entry=images.entries.get(imageRoute[1]);
        if(!entry)return json(response,404,{error:'图片暂不可用，请刷新会话后重试'});
        if(!allowedThreads.has(entry.threadId)){await list();if(!allowedThreads.has(entry.threadId))return json(response,404,{error:'此会话图片已不可用'});}
        const {bytes,mime}=await images.read(imageRoute[1]);
        response.writeHead(200,{'Content-Type':mime,'Content-Length':bytes.length,'Content-Disposition':'inline'});return response.end(bytes);
      }
      if(route==='/api/connection'&&request.method==='POST') {
        if(!local(request))return json(response,403,{error:'连接来源仅能在电脑设置'});
        const body=await readBody(request);
        if(!UUID.test(body.threadId??''))return json(response,400,{error:'聊天 ID 格式无效'});
        await saveConnectionId(body.threadId);bridge.callerThreadId=body.threadId; return json(response,200,{ok:true});
      }
      const match=route.match(/^\/api\/threads\/([^/]+)(\/messages)?$/);
      if(match) {
        const id=match[1];
        if(!UUID.test(id))return json(response,400,{error:'聊天 ID 格式无效'});
        if(!allowedThreads.has(id)) {await list();if(!allowedThreads.has(id))return json(response,404,{error:'聊天不存在或不属于本机 Codex'});}
        if(!match[2]&&request.method==='GET') {
          const cursor=url.searchParams.get('cursor'), key=`${id}:${cursor??''}`;
          if(cursor&&cursor.length>4000)return json(response,400,{error:'分页信息过长'});
          let entry=cache.get(key);
          if(!entry||entry.until<Date.now()) {
            entry={until:Date.now()+900,promise:bridge.read(id,cursor).then(data=>images.decorate(id,data)).catch(error=>{cache.delete(key);throw error;})};
            if(cache.size>100)cache.clear();cache.set(key,entry);
          }
          return json(response,200,await entry.promise);
        }
        if(match[2]&&request.method==='POST') {
          const body=await readBody(request);
          if(typeof body.prompt!=='string'||!body.prompt.trim()||body.prompt.length>16000||!UUID.test(body.requestId??''))return json(response,400,{error:'请输入消息（最多 16000 字），并携带有效请求 ID'});
          const fingerprint=createHash('sha256').update(JSON.stringify([id,body.prompt])).digest('hex');
          const known=receipts.get(body.requestId);
          if(known) {
            if(known.fingerprint!==fingerprint)return json(response,409,{error:'请求 ID 已用于另一条消息'});
            return json(response,known.state==='sent'?200:409,{state:known.state,result:known.result,error:known.state!=='sent'?'此消息已提交过，结果待确认；请查看聊天，勿重复发送':undefined});
          }
          const now=Date.now();
          if(!session.sendWindow||session.sendWindow.until<now)session.sendWindow={count:0,until:now+60000};
          if(session.sendWindow.count>=10)return json(response,429,{error:'消息发送过于频繁，请稍后再试'});
          session.sendWindow.count++;
          const record={id:body.requestId,fingerprint,state:'unknown',createdAt:Date.now()};
          receipts.set(record.id,record);await persist();
          try {
            const result=await bridge.send(id,body.prompt); record.state='sent';record.result=result;await persist(); cache.delete(`${id}:`);
            return json(response,200,{state:'sent',result});
          } catch(error) {return json(response,502,{state:'unknown',error:`发送结果待确认：${error.message}。请查看桌面聊天后再决定是否重新发送。`});}
        }
      }
      return json(response,404,{error:'没有此接口'});
    }catch(error){if(!response.headersSent)json(response,error.status??502,{error:error.message});else response.end();}
  };
  return {handler,get pairingCode(){return pairingCode;}};
}

async function main() {
  const runtime=path.join(ROOT,'.runtime');await mkdir(runtime,{recursive:true});
  let config={};try{config=JSON.parse(await readFile(path.join(runtime,'config.json'),'utf8'));}catch{}
  const bridge=new DesktopBridge({pipePath:process.env.CODEX_LAN_PIPE??config.pipePath,autoPipePath:process.env.CODEX_APP_TOOLS_PIPE_PATH??config.autoPipePath,callerThreadId:config.callerThreadId??process.env.CODEX_THREAD_ID,logRoot:config.logRoot});
  const atomicWrite=async(file,data)=>{const temp=file+'.tmp';await writeFile(temp,JSON.stringify(data,null,2),{mode:0o600});await rename(temp,file);};
  let configFlight=Promise.resolve();
  const updateConfig=patch=>{configFlight=configFlight.catch(()=>{}).then(async()=>{const next={...config,...patch};await atomicWrite(path.join(runtime,'config.json'),next);config=next;});return configFlight;};
  if(!/^[0-9]{8}$/.test(config.pairingCode??'')){
    let previous={};try{previous=JSON.parse(await readFile(path.join(runtime,'running.json'),'utf8'));}catch{}
    await updateConfig({pairingCode:/^[0-9]{8}$/.test(previous.pairingCode??'')?previous.pairingCode:String(randomInt(10000000,100000000))});
  }
  const sessionPath=path.join(runtime,'sessions.json');let initialSessions=[];
  try{initialSessions=JSON.parse(await readFile(sessionPath,'utf8'));}catch(error){if(error.code!=='ENOENT')throw new Error('会话记录损坏，请检查 .runtime/sessions.json');}
  const receiptPath=path.join(runtime,'receipts.json');let initialReceipts=[];
  try{initialReceipts=JSON.parse(await readFile(receiptPath,'utf8'));}catch(error){if(error.code!=='ENOENT')throw new Error('发送记录损坏，请先检查 .runtime/receipts.json，服务未启动');}
  const certificate=await readFile(path.join(runtime,'cert.pem'));
  const app=createApp({bridge,receiptPath,initialReceipts,certificate,pairingCode:config.pairingCode,initialSessions,saveSessions:s=>atomicWrite(sessionPath,s),
    savePairingCode:async pairingCode=>{await updateConfig({pairingCode});let info={};try{info=JSON.parse(await readFile(path.join(runtime,'running.json'),'utf8'));}catch{}await atomicWrite(path.join(runtime,'running.json'),{...info,pairingCode});},
    saveConnectionId:callerThreadId=>updateConfig({callerThreadId})});
  const tls=https.createServer({cert:certificate,key:await readFile(path.join(runtime,'key.pem'))},app.handler);
  const preview=http.createServer(app.handler);
  const start=(server,port,host)=>new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,host,resolve);});
  await start(tls,8787,'0.0.0.0');await start(preview,8788,'127.0.0.1');
  const info={pid:process.pid,pairingCode:app.pairingCode,urls:addresses().map(ip=>`https://${ip}:8787`),desktop:'http://127.0.0.1:8788'};
  await writeFile(path.join(runtime,'running.json'),JSON.stringify(info,null,2),{mode:0o600});
  console.log('Codex LAN 已启动。电脑连接面板：http://127.0.0.1:8788');
  console.log(`配对码：${app.pairingCode}`);info.urls.forEach(url=>console.log(`手机地址：${url}`));
  const shutdown=()=>{bridge.close();tls.close();preview.close();setTimeout(()=>process.exit(),1000).unref();};
  process.on('SIGINT',shutdown);process.on('SIGTERM',shutdown);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(error.message);process.exitCode=1;});
