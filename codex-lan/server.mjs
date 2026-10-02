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
import { localProjects } from './projects.mjs';
import { UploadStore, MAX_UPLOAD } from './uploads.mjs';
import { selectedModel } from './models.mjs';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const UUID = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i;
const json = (response, status, body) => { response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); response.end(JSON.stringify(body)); };
const equal = (a, b) => { const x=Buffer.from(a), y=Buffer.from(b); return x.length === y.length && timingSafeEqual(x,y); };
const local = request => ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(request.socket.remoteAddress);
export function addresses() {
  return Object.values(networkInterfaces()).flat().filter(i => i && !i.internal && i.family === 'IPv4' && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(i.address)).map(i => i.address);
}
async function readBinary(request) {
  let size=0;const parts=[];
  for await(const part of request){size+=part.length;if(size>MAX_UPLOAD)throw Object.assign(new Error('单个文件不能超过 20 MB'),{status:413});parts.push(part);}
  return Buffer.concat(parts);
}
async function readBody(request) {
  let bytes=0; const parts=[];
  for await (const part of request) {
    bytes+=part.length; if(bytes>65536) throw Object.assign(new Error('请求过大'),{status:413}); parts.push(part);
  }
  try { return JSON.parse(Buffer.concat(parts).toString('utf8')); }
  catch { throw Object.assign(new Error('请求格式错误'),{status:400}); }
}
export function createApp({ bridge, pairingCode=String(randomInt(10000000,100000000)), lanAddresses=addresses(), port=8787, receiptPath, uploadRoot, certificate, initialReceipts=[], initialSessions=[], saveSessions=async()=>{}, savePairingCode=async()=>{}, saveConnectionId=async()=>{} } = {}) {
  const sessions=new Map(initialSessions.filter(([hash,s])=>/^[a-f0-9]{64}$/.test(hash)&&s.expires>Date.now()&&typeof s.csrf==='string')), attempts=new Map(), allowedThreads=new Set(), cache=new Map();
  const images=new ImageRegistry();
  const uploads=new UploadStore(uploadRoot);
  let activeUploads=0;
  let usageCache, usageFlight;
  let projectCache = [], projectUntil = 0, projectNotice = '';
  let modelsCache, modelsFlight, modelsFetchedAt = 0;
  async function models(force = false) {
    if (modelsCache && Date.now()-modelsFetchedAt < (force?5000:60000)) return modelsCache;
    if (!modelsFlight) modelsFlight = bridge.models().then(result => { modelsCache=result;modelsFetchedAt=Date.now();return result; }).finally(()=>{modelsFlight=null;});
    return modelsFlight;
  }
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
    listFlight=bridge.list().then(async result=>{
      const threads=[...(result.pinnedThreads??[]),...(result.threads??[])].filter(t=>t.kind==='codex' && (t.hostId??'local')==='local');
      allowedThreads.clear(); threads.forEach(t=>allowedThreads.add(t.id));
      if (Date.now() >= projectUntil) {
        projectUntil = Date.now() + 60000;
        try {
          projectCache = localProjects(await bridge.projects()); projectNotice = '';
        } catch { projectNotice = '项目名称暂不可用，请稍后刷新或更新电脑服务。'; }
      }
      return {threads, projects: projectCache, projectsNotice: projectNotice};
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
      if(route==='/api/models'&&request.method==='GET')return json(response,200,await models(url.searchParams.get('refresh')==='1'));
      const uploadImage=route.match(/^\/api\/upload-images\/([^/]+)\/([^/]+)$/);
      if(uploadImage&&request.method==='GET'){
        const [,threadId,id]=uploadImage;if(!UUID.test(threadId)||!UUID.test(id))return json(response,404,{error:'图片地址无效'});
        if(!allowedThreads.has(threadId)){await list();if(!allowedThreads.has(threadId))return json(response,404,{error:'会话已不可用'});}
        const {bytes,mime}=await uploads.image(threadId,id);response.writeHead(200,{'Content-Type':mime,'Content-Length':bytes.length,'Content-Disposition':'inline'});return response.end(bytes);
      }
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
      const match=route.match(/^\/api\/threads\/([^/]+)(\/messages|\/uploads|\/permissions|\/open-desktop)?$/);
      if(match) {
        const id=match[1];
        if(!UUID.test(id))return json(response,400,{error:'聊天 ID 格式无效'});
        if(!allowedThreads.has(id)) {await list();if(!allowedThreads.has(id))return json(response,404,{error:'聊天不存在或不属于本机 Codex'});}
        if(match[2]==='/uploads'&&request.method==='POST') {
          const now=Date.now();if(!session.uploadWindow||session.uploadWindow.until<now)session.uploadWindow={count:0,until:now+60000};
          if(session.uploadWindow.count>=10)return json(response,429,{error:'上传过于频繁，请稍后再试'});session.uploadWindow.count++;
          if(activeUploads>=2)return json(response,429,{error:'正在处理其他附件，请稍后重试'});
          activeUploads++;try{return json(response,200,await uploads.put(id,url.searchParams.get('requestId'),url.searchParams.get('name'),await readBinary(request)));}finally{activeUploads--;}
        }
        if(match[2]==='/permissions'&&request.method==='GET')return json(response,200,{editable:false,mode:'desktop',message:'此连接接口暂不提供权限修改。会话继续使用 Desktop 中设置的权限。请在电脑的输入区点击盾牌修改。'});
        if(match[2]==='/open-desktop'&&request.method==='POST'){await bridge.openDesktop(id);return json(response,200,{ok:true});}
        if(!match[2]&&request.method==='GET') {
          const cursor=url.searchParams.get('cursor'), key=`${id}:${cursor??''}`;
          if(cursor&&cursor.length>4000)return json(response,400,{error:'分页信息过长'});
          let entry=cache.get(key);
          if(!entry||entry.until<Date.now()) {
            entry={until:Date.now()+900,promise:bridge.read(id,cursor).then(data=>uploads.decorate(id,images.decorate(id,data))).catch(error=>{cache.delete(key);throw error;})};
            if(cache.size>100)cache.clear();cache.set(key,entry);
          }
          return json(response,200,await entry.promise);
        }
        if(match[2]==='/messages'&&request.method==='POST') {
          const body=await readBody(request);
          if(typeof body.prompt!=='string'||(!body.prompt.trim()&&!body.attachments?.length)||body.prompt.length>16000||!UUID.test(body.requestId??''))return json(response,400,{error:'请输入消息（最多 16000 字），并携带有效请求 ID'});
          const options = body.model === undefined && body.thinking === undefined ? {} : selectedModel(body, await models().catch(() => {
            throw Object.assign(new Error('模型列表暂不可用，请刷新或选择跟随桌面后发送'), {status:400});
          }));
          const ids=body.attachments??[];
          if(!Array.isArray(ids)||ids.length>5||new Set(ids).size!==ids.length||ids.some(v=>typeof v!=='string'||!UUID.test(v)))return json(response,400,{error:'附件编号无效'});
          const identity=options.model?[id,body.prompt,options.model,options.thinking??null]:[id,body.prompt];
          if(ids.length)identity.push(ids);
          const fingerprint=createHash('sha256').update(JSON.stringify(identity)).digest('hex');
          const known=receipts.get(body.requestId);
          if(known) {
            if(known.fingerprint!==fingerprint)return json(response,409,{error:'请求 ID 已用于另一条消息'});
            return json(response,known.state==='sent'?200:409,{state:known.state,result:known.result,error:known.state!=='sent'?'此消息已提交过，结果待确认；请查看聊天，勿重复发送':undefined});
          }
          const attachmentRows=await uploads.selected(id,ids);
          const wirePrompt=uploads.prompt(body.prompt,attachmentRows);
          if(wirePrompt.length>16000)return json(response,400,{error:'消息与附件信息合计超过 16000 字，请缩短消息'});
          const now=Date.now();
          if(!session.sendWindow||session.sendWindow.until<now)session.sendWindow={count:0,until:now+60000};
          if(session.sendWindow.count>=10)return json(response,429,{error:'消息发送过于频繁，请稍后再试'});
          session.sendWindow.count++;
          const record={id:body.requestId,fingerprint,state:'unknown',createdAt:Date.now()};
          receipts.set(record.id,record);await persist();
          try {
            const result=await bridge.send(id,wirePrompt,options); record.state='sent';record.result=result;await persist(); cache.delete(`${id}:`);
            return json(response,200,{state:'sent',result});
          } catch(error) {return json(response,502,{state:'unknown',error:`发送结果待确认：${error.message}。请查看桌面聊天后再决定是否重新发送。`});}
        }
      }
      return json(response,404,{error:'没有此接口'});
    }catch(error){if(!response.headersSent)json(response,error.status??502,{error:error.message});else response.end();}
  };
  return {handler,ready:uploads.ready,get pairingCode(){return pairingCode;}};
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
  const app=createApp({bridge,receiptPath,uploadRoot:path.join(runtime,'uploads'),initialReceipts,certificate,pairingCode:config.pairingCode,initialSessions,saveSessions:s=>atomicWrite(sessionPath,s),
    savePairingCode:async pairingCode=>{await updateConfig({pairingCode});let info={};try{info=JSON.parse(await readFile(path.join(runtime,'running.json'),'utf8'));}catch{}await atomicWrite(path.join(runtime,'running.json'),{...info,pairingCode});},
    saveConnectionId:callerThreadId=>updateConfig({callerThreadId})});
  await app.ready;
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
