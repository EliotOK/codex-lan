import {spawn} from 'node:child_process';
import {mkdir,open,appendFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url)),runtime=path.join(root,'.runtime');
await mkdir(runtime,{recursive:true});
await writeFile(path.join(runtime,'watcher.json'),JSON.stringify({pid:process.pid,startedAt:new Date().toISOString()}));
let child,stopping=false,restart;
const log=message=>appendFile(path.join(runtime,'watch.log'),`${new Date().toISOString()} ${message}\n`);
async function start(){
  if(stopping)return;
  const stdout=await open(path.join(runtime,'server.log'),'a'),stderr=await open(path.join(runtime,'server-error.log'),'a');
  child=spawn(process.execPath,[path.join(root,'server.mjs')],{cwd:root,windowsHide:true,stdio:['ignore',stdout.fd,stderr.fd]});
  child.once('error',error=>{void log(error.message);});
  child.once('close',(code,signal)=>{void log(`server exited code=${code} signal=${signal}`);if(!stopping)restart=setTimeout(()=>{void start().catch(error=>{void log(error.message);process.exitCode=1;});},3000);});
  await Promise.all([stdout.close(),stderr.close()]);
  await log(`started server pid=${child.pid}`);
}
const stop=()=>{stopping=true;clearTimeout(restart);child?.kill();setTimeout(()=>process.exit(),2000).unref();};
process.on('SIGINT',stop);process.on('SIGTERM',stop);
await start();
