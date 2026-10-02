import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdir,mkdtemp,writeFile,unlink} from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {ImageRegistry,markdownImages,MAX_IMAGE_BYTES} from '../images.mjs';
import {createApp} from '../server.mjs';
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
async function folder(){const parent=fileURLToPath(new URL('../.runtime/tests/',import.meta.url));await mkdir(parent,{recursive:true});return mkdtemp(path.join(parent,'images-'));}
test('Markdown image destinations support spaces, brackets, escaped paths and reference labels',()=>{
 const text='![one](<a b.png>)\n![two](dir/pic(1).png "caption")\n![three](dir/a\\(2\\).png)\n`![code](secret.png)`\n```\n![hidden](secret2.png)\n```\n![repeat][ref]\n![second][ref]\n[ref]: <other image.png>';
 assert.deepEqual(markdownImages(text).map(r=>r.source),['a b.png','dir/pic(1).png','dir/a(2).png','other image.png']);
});
test('registry decorates snapshots with stable opaque references and serves verified raster data',async()=>{
 const cwd=await folder();const file=path.join(cwd,'图 (1).png');await writeFile(file,png);
 const registry=new ImageRegistry();const thread=randomUUID();
 const snapshot={thread:{cwd},turns:[{items:[{type:'userMessage',content:[{type:'localImage',path:file}]},{type:'agentMessage',text:'![figure](<图 (1).png>)'}]}]};
 const decorated=registry.decorate(thread,snapshot);const id=decorated.turns[0].items[0].content[0].imageId;
 assert.match(id,/^[a-f0-9]{64}$/);assert.ok(decorated.turns[0].items[1].text.includes('/api/images/'+id));assert.equal(snapshot.turns[0].items[0].content[0].imageId,undefined);
 assert.deepEqual((await registry.read(id)).bytes,png);
 assert.equal(registry.register(thread,file,cwd).id,id);
 assert.notEqual(registry.register(randomUUID(),file,cwd).id,id);
 const data=registry.register(thread,'data:image/png;base64,'+png.toString('base64'),cwd);
 assert.equal((await registry.read(data.id)).mime,'image/png');
 await unlink(file);await assert.rejects(registry.read(id),error=>error.status===404);
});
test('image registry rejects non-image contents and oversized files',async()=>{
 const cwd=await folder();const registry=new ImageRegistry();const thread=randomUUID();
 const fake=path.join(cwd,'fake.png');await writeFile(fake,'not an image');
 await assert.rejects(registry.read(registry.register(thread,fake,cwd).id),error=>error.status===415);
 const large=path.join(cwd,'large.png');await writeFile(large,Buffer.alloc(MAX_IMAGE_BYTES+1));
 await assert.rejects(registry.read(registry.register(thread,large,cwd).id),error=>error.status===413);
 assert.equal(registry.register(thread,path.join(cwd,'key.pem'),cwd),null);
 assert.equal(registry.register(thread,'https://example.com/image.png',cwd),null);
});
test('image route requires pairing, a registered reference and access to the originating thread',async t=>{
 const cwd=await folder();const file=path.join(cwd,'image.png');await writeFile(file,png);const thread=randomUUID();let visible=true;
 const bridge={list:async()=>({threads:visible?[{id:thread,kind:'codex'}]:[]}),read:async()=>({thread:{id:thread,cwd},turns:[{items:[{type:'userMessage',content:[{type:'localImage',path:file}]}]}]})};
 const app=createApp({bridge,pairingCode:'12345678',lanAddresses:[]});const server=http.createServer(app.handler);await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>server.close(r)));
 const base='http://127.0.0.1:'+server.address().port;
 assert.equal((await fetch(base+'/api/images/'+'0'.repeat(64))).status,401);
 const pair=await fetch(base+'/api/pair',{method:'POST',body:'{"code":"12345678"}'});const headers={Cookie:pair.headers.get('set-cookie').split(';')[0]};
 const snapshot=await(await fetch(base+'/api/threads/'+thread,{headers})).json();const id=snapshot.turns[0].items[0].content[0].imageId;
 const response=await fetch(base+'/api/images/'+id,{headers});assert.equal(response.status,200);assert.equal(response.headers.get('content-type'),'image/png');assert.equal(response.headers.get('cache-control'),'no-store');assert.deepEqual(Buffer.from(await response.arrayBuffer()),png);
 assert.equal((await fetch(base+'/api/images/'+'0'.repeat(64),{headers})).status,404);
 visible=false;await fetch(base+'/api/threads',{headers});assert.equal((await fetch(base+'/api/images/'+id,{headers})).status,404);
});
