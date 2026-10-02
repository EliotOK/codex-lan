import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import { randomUUID } from 'node:crypto';
import { mkdtemp, readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { createApp } from '../server.mjs';
import { DesktopBridge, encodeFrame } from '../bridge.mjs';

const threadId=randomUUID();
async function fixture(t,options={}) {
  const calls=[];
  const bridge={callerThreadId:randomUUID(),check:async()=>({connected:true}),list:async()=>({pinnedThreads:[],threads:[{id:threadId,kind:'codex',hostId:'local',title:'test'}]}),read:async id=>({thread:{id,status:{type:'active'}},turns:[]}),send:async(id,prompt)=>{calls.push({id,prompt});return{accepted:true};},...options.bridge};
  const app=createApp({bridge,pairingCode:'12345678',lanAddresses:[],...options});
  const server=http.createServer(app.handler);await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise(resolve=>server.close(resolve)));
  const base=`http://127.0.0.1:${server.address().port}`;
  const request=async(url,opts={})=>{const response=await fetch(base+url,opts);return{status:response.status,data:await response.json(),response};};
  const pair=async()=>{
    const result=await request('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:'12345678'})});
    return{Cookie:result.response.headers.get('set-cookie').split(';')[0],'X-CSRF-Token':result.data.csrf,'Content-Type':'application/json'};
  };
  const rawHost=host=>new Promise((resolve,reject)=>{const r=http.request(base+'/api/local-info',{headers:{Host:host}},response=>{response.resume();response.on('end',()=>resolve(response.statusCode));});r.on('error',reject);r.end();});
  return{request,pair,calls,bridge,rawHost};
}

test('authentication, CSRF, host checks and fixed routes protect desktop operations',async t=>{
  const f=await fixture(t);
  assert.equal((await f.request('/api/threads')).status,401);
  assert.equal((await f.request('/api/local-info')).data.pairingCode,'12345678');
  assert.equal(await f.rawHost('attacker.example'),403);
  assert.equal((await f.request('/api/pair',{method:'POST',headers:{Origin:'https://attacker.example'},body:'{"code":"12345678"}'})).status,403);
  const headers=await f.pair();
  assert.equal((await f.request('/api/threads',{headers})).data.threads[0].id,threadId);
  assert.equal((await f.request('/api/threads/'+randomUUID(),{headers})).status,404);
  const body=JSON.stringify({prompt:'hello',requestId:randomUUID()});
  assert.equal((await f.request(`/api/threads/${threadId}/messages`,{method:'POST',headers:{Cookie:headers.Cookie},body})).status,403);
  assert.equal((await f.request('/api/rpc',{method:'POST',headers,body:'{}'})).status,404);
  assert.equal(f.calls.length,0);
});

test('a message request is durably recorded and repeated submissions are forwarded once',async t=>{
  const tempRoot=fileURLToPath(new URL('../.runtime/tests/',import.meta.url));await mkdir(tempRoot,{recursive:true});
  const directory=await mkdtemp(path.join(tempRoot,'receipts-'));
  const receiptPath=path.join(directory,'receipts.json');
  const f=await fixture(t,{receiptPath});const headers=await f.pair();const requestId=randomUUID();
  const body=JSON.stringify({prompt:'你好',requestId});
  const first=await f.request(`/api/threads/${threadId}/messages`,{method:'POST',headers,body});
  assert.equal(first.data.state,'sent');
  const repeat=await f.request(`/api/threads/${threadId}/messages`,{method:'POST',headers,body});
  assert.equal(repeat.data.state,'sent');assert.equal(f.calls.length,1);
  const altered=await f.request(`/api/threads/${threadId}/messages`,{method:'POST',headers,body:JSON.stringify({prompt:'different',requestId})});
  assert.equal(altered.status,409);assert.equal(f.calls.length,1);
  const saved=JSON.parse(await readFile(receiptPath,'utf8'));assert.equal(saved[0].state,'sent');
  assert.equal(JSON.stringify(saved).includes('你好'),false);
  const restarted=await fixture(t,{initialReceipts:saved});const nextHeaders=await restarted.pair();
  assert.equal((await restarted.request(`/api/threads/${threadId}/messages`,{method:'POST',headers:nextHeaders,body})).data.state,'sent');assert.equal(restarted.calls.length,0);
});

test('unknown send outcomes block automatic resubmission',async t=>{
  let sends=0;
  const f=await fixture(t,{bridge:{callerThreadId:randomUUID(),list:async()=>({threads:[{id:threadId,kind:'codex',hostId:'local'}]}),send:async()=>{sends++;throw new Error('lost acknowledgement');}}});
  const headers=await f.pair(),body=JSON.stringify({prompt:'message',requestId:randomUUID()});
  assert.equal((await f.request(`/api/threads/${threadId}/messages`,{method:'POST',headers,body})).data.state,'unknown');
  assert.equal((await f.request(`/api/threads/${threadId}/messages`,{method:'POST',headers,body})).status,409);
  assert.equal(sends,1);
});

test('pairing attempts are limited and logout revokes the session',async t=>{
  const f=await fixture(t);
  const headers=await f.pair();
  assert.equal((await f.request('/api/logout',{method:'POST',headers,body:'{}'})).status,200);
  assert.equal((await f.request('/api/session',{headers})).status,401);
  for(let i=0;i<5;i++)assert.equal((await f.request('/api/pair',{method:'POST',body:'{"code":"00000000"}'})).status,401);
  assert.equal((await f.request('/api/pair',{method:'POST',body:'{"code":"12345678"}'})).status,429);
});

test('paired sessions survive restart as token hashes and logout remains revoked',async t=>{
  let saved=[];
  const f=await fixture(t,{saveSessions:async sessions=>{saved=JSON.parse(JSON.stringify(sessions));}});
  const headers=await f.pair(),token=headers.Cookie.split('=')[1];
  assert.equal(JSON.stringify(saved).includes(token),false);
  const restarted=await fixture(t,{initialSessions:saved,saveSessions:async sessions=>{saved=JSON.parse(JSON.stringify(sessions));}});
  assert.equal((await restarted.request('/api/session',{headers})).status,200);
  await restarted.request('/api/logout',{method:'POST',headers,body:'{}'});
  const next=await fixture(t,{initialSessions:saved});
  assert.equal((await next.request('/api/session',{headers})).status,401);
});
test('pairing code changes are validated, saved, and keep existing devices paired',async t=>{
  let savedCode;
  const f=await fixture(t,{savePairingCode:async code=>{savedCode=code;}}),headers=await f.pair();
  assert.equal((await f.request('/api/settings/pairing-code',{method:'POST',headers,body:'{"code":"abc"}'})).status,400);
  assert.equal((await f.request('/api/settings/pairing-code',{method:'POST',headers:{Cookie:headers.Cookie},body:'{"code":"87654321"}'})).status,403);
  assert.equal((await f.request('/api/settings/pairing-code',{method:'POST',headers,body:'{"code":"87654321"}'})).data.pairingCode,'87654321');
  assert.equal(savedCode,'87654321');
  assert.equal((await f.request('/api/local-info')).data.pairingCode,'87654321');
  assert.equal((await f.request('/api/session',{headers})).status,200);
  assert.equal((await f.request('/api/pair',{method:'POST',body:'{"code":"12345678"}'})).status,401);
  assert.equal((await f.request('/api/pair',{method:'POST',body:'{"code":"87654321"}'})).status,200);
});
test('IPC timeout discards the stale connection and next read opens a fresh connection',async t=>{
  let count=0;
  const sockets=new Set();
  const server=net.createServer(socket=>{
    const attempt=++count;sockets.add(socket);socket.once('close',()=>sockets.delete(socket));
    let input=Buffer.alloc(0);
    socket.on('data',data=>{
      input=Buffer.concat([input,data]);
      if(input.length<4||input.length<input.readUInt32LE(0)+4)return;
      const request=JSON.parse(input.subarray(4,input.readUInt32LE(0)+4));
      if(attempt>1)socket.write(encodeFrame({id:request.id,result:{connected:true}}));
    });
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const bridge=new DesktopBridge({pipePath:{host:'127.0.0.1',port:server.address().port}});
  t.after(()=>{bridge.close();for(const socket of sockets)socket.destroy();return new Promise(resolve=>server.close(resolve));});
  await assert.rejects(bridge.request('tools/list',{},50),/超时/);
  assert.equal(bridge.socket,null);assert.equal(bridge.pending.size,0);
  assert.equal((await bridge.request('tools/list',{},1500)).connected,true);
  assert.equal(count,2);
});

test('pipe decoder handles fragmented and combined frames, rejects errors and oversized frames',()=>{
  const bridge=new DesktopBridge();const received=[];
  for(let id=1;id<=3;id++)bridge.pending.set(id,{resolve:v=>received.push(v),reject:e=>received.push(e.message),timer:setTimeout(()=>{},10000)});
  const first=encodeFrame({id:1,result:{ok:true}}),second=encodeFrame({id:2,error:{message:'denied'}});
  bridge.onData(first.subarray(0,2));assert.equal(received.length,0);
  bridge.onData(Buffer.concat([first.subarray(2),second]));assert.deepEqual(received,[{ok:true},'denied']);
  const oversized=Buffer.alloc(4);oversized.writeUInt32LE(9*1024*1024);bridge.onData(oversized);
  assert.equal(bridge.pending.size,0);assert.match(received[2],/断开/);
});
