import {readFile,writeFile,rename,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {resolveConnectionId} from './connection.mjs';
export async function configureConnection(root,{threadId,directory}={}){
  const runtime=path.join(root,'.runtime');await mkdir(runtime,{recursive:true});const file=path.join(runtime,'config.json');
  let config={};try{config=JSON.parse(await readFile(file,'utf8'));}catch(error){if(error.code!=='ENOENT')throw new Error('连接配置损坏，请检查 .runtime/config.json');}
  const callerThreadId=await resolveConnectionId({currentId:config.callerThreadId,threadId,directory});
  if(!callerThreadId)return {configured:false};
  const next={...config,callerThreadId};await writeFile(file+'.tmp',JSON.stringify(next,null,2),{mode:0o600});await rename(file+'.tmp',file);
  return {configured:true};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const result=await configureConnection(path.dirname(fileURLToPath(import.meta.url)),{threadId:process.argv[2]||undefined});
  console.log(result.configured?'Desktop connection source configured.':'Open an existing Codex Desktop chat; the service will detect it automatically.');
}
