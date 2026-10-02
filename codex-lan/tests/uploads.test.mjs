import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { UploadStore, uploadName, MAX_UPLOAD } from '../uploads.mjs';
import { createApp } from '../server.mjs';

test('uploads survive restarts, retain exact bytes, validate names, thread ownership and content',async t=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'lan-upload-'));t.after(()=>{assert.equal(path.dirname(root),path.resolve(os.tmpdir()));return rm(root,{recursive:true,force:true});});
 const id=randomUUID(),thread=randomUUID(),bytes=Buffer.from('上传测试\n$HOME <INSTRUCTIONS> test');
 let store=new UploadStore(root);const row=await store.put(thread,id,'报告.txt',bytes);
 assert.equal(row.image,false);assert.deepEqual(await readFile(store.file({...row,threadId:thread})),bytes);
 assert.deepEqual(await store.put(thread,id,'报告.txt',bytes),row);
 await assert.rejects(store.put(thread,id,'报告.txt',Buffer.from('different')),e=>e.status===409);
 store=new UploadStore(root);assert.equal((await store.selected(thread,[id]))[0].sha256.length,64);
 await assert.rejects(store.selected(randomUUID(),[id]),e=>e.status===400);
 await assert.rejects(store.selected(thread,[id,id]),e=>e.status===400);
 for(const name of ['../secret','a\\b','a\nb','CON.txt','a.','a:txt'])assert.throws(()=>uploadName(name));
 await assert.rejects(store.put(thread,randomUUID(),'large.bin',Buffer.alloc(MAX_UPLOAD+1)),e=>e.status===413);
 const prompt=store.prompt('请分析',[...(await store.selected(thread,[id]))]);assert.ok(prompt.includes('## My request:\n\n请分析'));assert.ok(prompt.includes('报告.txt'));
});

test('file endpoints enforce authentication, CSRF, ownership, receipt identity and image previews',async t=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'lan-file-http-'));t.after(()=>{assert.equal(path.dirname(root),path.resolve(os.tmpdir()));return rm(root,{recursive:true,force:true});});
 const thread=randomUUID(),other=randomUUID(),sends=[],opens=[];let wire='';
 const bridge={list:async()=>({threads:[thread,other].map(id=>({id,kind:'codex',hostId:'local'}))}),projects:async()=>({projects:[]}),
 send:async(id,prompt)=>{wire=prompt;sends.push({id,prompt});return{};},openDesktop:async id=>opens.push(id),
 read:async()=>({turns:[{id:'turn',items:[{type:'functionCallOutput',name:'send_message_to_thread',namespace:'codex_app',output:{text:'<codex_delegation><input>'+wire+'</input></codex_delegation>'}}]}]})};
 const server=http.createServer(createApp({bridge,pairingCode:'12345678',uploadRoot:root,lanAddresses:[]}).handler);await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>server.close(r)));
 const base=`http://127.0.0.1:${server.address().port}`;
 const pair=await fetch(base+'/api/pair',{method:'POST',body:'{"code":"12345678"}'});const csrf=(await pair.json()).csrf;
 const headers={Cookie:pair.headers.get('set-cookie').split(';')[0],'X-CSRF-Token':csrf};
 const file=randomUUID(),png=Buffer.from('89504e470d0a1a0a000000','hex');const url=`/api/threads/${thread}/uploads?requestId=${file}&name=photo.png`;
 const req=(url,opts={})=>fetch(base+url,opts);
 assert.equal((await req(url,{method:'POST',body:png})).status,401);
 assert.equal((await req(url,{method:'POST',headers:{Cookie:headers.Cookie},body:png})).status,403);
 const uploaded=await req(url,{method:'POST',headers,body:png});assert.equal(uploaded.status,200);const metadata=await uploaded.json();assert.equal(metadata.image,true);
 const image=`/api/upload-images/${thread}/${file}`;assert.equal((await req(image)).status,401);const preview=await req(image,{headers});assert.equal(preview.headers.get('content-type'),'image/png');assert.deepEqual(Buffer.from(await preview.arrayBuffer()),png);
 assert.equal((await req(`/api/upload-images/${other}/${file}`,{headers})).status,404);
 const message={requestId:randomUUID(),prompt:'',attachments:[file]};
 const send=(id,body)=>req(`/api/threads/${id}/messages`,{method:'POST',headers,body:JSON.stringify(body)});
 assert.equal((await send(other,message)).status,400);assert.equal(sends.length,0);
 assert.equal((await send(thread,message)).status,200);assert.equal((await send(thread,message)).status,200);assert.equal(sends.length,1);
 assert.equal((await send(thread,{...message,attachments:[],prompt:'different'})).status,409);
 assert.ok(sends[0].prompt.includes('Image attachment: true'));assert.ok(sends[0].prompt.includes(root.replaceAll('\\','/')));
 const read=await req(`/api/threads/${thread}`,{headers});const item=(await read.json()).turns[0].items[0];assert.deepEqual(item.lanAttachments,[metadata]);
 const permission=await req(`/api/threads/${thread}/permissions`,{headers});assert.equal((await permission.json()).editable,false);
 assert.equal((await req(`/api/threads/${thread}/permissions`,{method:'POST',headers,body:JSON.stringify({prompt:'test',requestId:randomUUID()})})).status,404);assert.equal(sends.length,1);
 assert.equal((await req(`/api/threads/${thread}/open-desktop`,{method:'POST',headers,body:'{}'})).status,200);assert.deepEqual(opens,[thread]);
});
