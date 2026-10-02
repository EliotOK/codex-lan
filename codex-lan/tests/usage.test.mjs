import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {normalizeUsage} from '../usage.mjs';
import {createApp} from '../server.mjs';
test('usage prefers named buckets, clamps remaining and excludes account identifiers',()=>{
 const result=normalizeUsage({accountId:'private',rateLimitResetCredits:{credits:['secret']},rateLimits:{primary:{usedPercent:99}},rateLimitsByLimitId:{codex:{primary:{usedPercent:25,windowDurationMins:300,resetsAt:2000000000},secondary:{usedPercent:null}},model:{primary:{usedPercent:120},secondary:{usedPercent:-10}}}},123);
 assert.equal(result.fetchedAt,123);assert.equal(result.limits.length,2);
 assert.equal(result.limits[0].windows[0].remainingPercent,75);assert.equal(result.limits[0].windows[1].remainingPercent,null);
 assert.deepEqual(result.limits[1].windows.map(w=>w.remainingPercent),[0,100]);assert.ok(!JSON.stringify(result).includes('private'));assert.ok(!JSON.stringify(result).includes('secret'));
 assert.equal(normalizeUsage({rateLimits:{primary:{usedPercent:0}}}).limits[0].windows[0].remainingPercent,100);
 assert.deepEqual(normalizeUsage({}).limits,[]);
});
test('usage route requires pairing, coalesces reads and caches manual refresh',async t=>{
 let reads=0;const bridge={usage:async()=>{reads++;await new Promise(r=>setTimeout(r,20));return{rateLimits:{primary:{usedPercent:10}}};}};
 const app=createApp({bridge,pairingCode:'12345678',lanAddresses:[]});const server=http.createServer(app.handler);await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>server.close(r)));
 const base='http://127.0.0.1:'+server.address().port;
 assert.equal((await fetch(base+'/api/usage')).status,401);
 const pair=await fetch(base+'/api/pair',{method:'POST',body:'{"code":"12345678"}'});const headers={Cookie:pair.headers.get('set-cookie').split(';')[0]};
 const responses=await Promise.all(Array.from({length:4},()=>fetch(base+'/api/usage',{headers}).then(r=>r.json())));
 assert.equal(reads,1);assert.equal(responses[0].limits[0].windows[0].remainingPercent,90);
 await fetch(base+'/api/usage?refresh=1',{headers});assert.equal(reads,1);
});
