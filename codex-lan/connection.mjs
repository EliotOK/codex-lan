import {readdir} from 'node:fs/promises';
import path from 'node:path';
const UUID=/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i;
export const codexDirectory=()=>process.env.CODEX_HOME??path.join(process.env.USERPROFILE??process.env.HOME??'', '.codex');
export async function recentDesktopThreads(directory=codexDirectory()){
  let database;
  try{
    const files=(await readdir(directory)).filter(name=>/^state_\d+\.sqlite$/.test(name)).sort((a,b)=>Number(b.match(/\d+/)[0])-Number(a.match(/\d+/)[0]));
    if(!files.length)return [];
    const {DatabaseSync}=await import('node:sqlite');
    database=new DatabaseSync(path.join(directory,files[0]),{readOnly:true,timeout:500});
    const columns=new Set(database.prepare('PRAGMA table_info(threads)').all().map(row=>row.name));
    if(!['id','source','archived'].every(name=>columns.has(name)))return [];
    const filters=["archived=0", "source='vscode'"];
    if(columns.has('thread_source'))filters.push("(thread_source='user' OR thread_source IS NULL)");
    const order=['recency_at_ms','updated_at_ms','updated_at'].filter(name=>columns.has(name)).map(name=>`${name} DESC`).join(',')||'id';
    return database.prepare(`SELECT id FROM threads WHERE ${filters.join(' AND ')} ORDER BY ${order} LIMIT 20`).all().map(row=>row.id).filter(id=>UUID.test(id));
  }catch{return [];}finally{database?.close();}
}
export async function resolveConnectionId({currentId,threadId,directory}={}){
  if(threadId!=null){if(!UUID.test(threadId))throw new Error('连接来源聊天 ID 格式无效');return threadId;}
  if(UUID.test(currentId??''))return currentId;
  if(UUID.test(process.env.CODEX_THREAD_ID??''))return process.env.CODEX_THREAD_ID;
  return (await recentDesktopThreads(directory))[0]??null;
}
