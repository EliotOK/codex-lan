import {createHmac,randomBytes} from 'node:crypto';
import {open} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export const MAX_IMAGE_BYTES=20*1024*1024;
const extensions=new Set(['.png','.jpg','.jpeg','.jpe','.gif','.webp','.bmp']);
const fail=(status,message)=>Object.assign(new Error(message),{status});
export function imageMime(bytes){
  if(bytes.length>=8&&bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))return 'image/png';
  if(bytes.length>=3&&bytes[0]===255&&bytes[1]===216&&bytes[2]===255)return 'image/jpeg';
  if(/^GIF8[79]a$/.test(bytes.subarray(0,6).toString('ascii')))return 'image/gif';
  if(bytes.subarray(0,4).toString('ascii')==='RIFF'&&bytes.subarray(8,12).toString('ascii')==='WEBP')return 'image/webp';
  if(bytes.length>=14&&bytes.subarray(0,2).toString('ascii')==='BM')return 'image/bmp';
  throw fail(415,'此文件不是支持的图片格式');
}

export function markdownImages(text){
  const mask=text.replace(/```[\s\S]*?(?:```|$)|~~~[\s\S]*?(?:~~~|$)|`[^`\n]*`/g,s=>' '.repeat(s.length));
  const found=[];
  for(const match of mask.matchAll(/!\[([^\]\n]*)\]\(/g)){
    if(match.index>0&&mask[match.index-1]==='\\')continue;
    let start=match.index+match[0].length;
    while(/[ \t]/.test(mask[start]??''))start++;
    let end=start,wrapped=false,depth=0;
    if(mask[start]==='<'){
      wrapped=true;start++;end=mask.indexOf('>',start);if(end<0||mask.slice(start,end).includes('\n'))continue;
    }else{
      while(end<mask.length){
        const c=mask[end];
        if(c==='\\'&&/[\\()<> ]/.test(mask[end+1]??'')){end+=2;continue;}
        if(c==='(')depth++;
        if(c===')'){if(depth===0)break;depth--;}
        if(/\s/.test(c)&&depth===0)break;
        end++;
      }
    }
    if(end===start)continue;
    const suffix=mask.slice(end+(wrapped?1:0));
    if(!/^\s*(?:(?:"[^"\n]*"|'[^'\n]*'|\([^\)\n]*\))\s*)?\)/.test(suffix))continue;
    found.push({start,end,source:text.slice(start,end).replace(/\\([\\()<> ])/g,'$1'),name:match[1]||'图片'});
  }
  const labels=new Set([...mask.matchAll(/!\[([^\]\n]+)\](?:\[([^\]\n]*)\])?(?!\()/g)].map(m=>(m[2]||m[1]).toLowerCase()));
  for(const definition of mask.matchAll(/^ {0,3}\[([^\]\r\n]+)\]:[ \t]*(<([^>\r\n]+)>|([^\s]+))/gm)){
    if(!labels.has(definition[1].toLowerCase()))continue;
    const source=definition[3]??definition[4];
    const start=definition.index+definition[0].indexOf(definition[2])+(definition[3]?1:0);
    found.push({start,end:start+source.length,source:source.replace(/\\([\\()<> ])/g,'$1'),name:definition[1]});
  }
  return [...new Map(found.map(r=>[`${r.start}:${r.end}`,r])).values()].sort((a,b)=>a.start-b.start);
}

export class ImageRegistry {
  constructor(){this.secret=randomBytes(32);this.entries=new Map();this.dataSize=0;}
  register(threadId,source,cwd,name='图片'){
    if(typeof source!=='string'||source.includes('\0'))return null;
    let entry;
    if(/^data:image\/(?:png|jpeg|gif|webp|bmp);base64,/i.test(source)){
      if(source.length>Math.ceil(MAX_IMAGE_BYTES*4/3)+100)return null;
      entry={data:source.slice(source.indexOf(',')+1),name};
    }else{
      if(source.length>4096||/^https?:/i.test(source))return null;
      let file=source;
      try{if(file.startsWith('file:'))file=fileURLToPath(file);else file=decodeURIComponent(file);}catch{return null;}
      if(/^\/[a-z]:[\\/]/i.test(file))file=file.slice(1);
      if(!extensions.has(path.extname(file).toLowerCase()))return null;
      if(!path.isAbsolute(file)){
        if(!cwd||!path.isAbsolute(cwd))return null;
        file=path.resolve(cwd,file);
      }
      entry={file:path.normalize(file),name:name==='图片'?path.basename(file):name};
    }
    const id=createHmac('sha256',this.secret).update(threadId+'\0'+(entry.file??entry.data)).digest('hex');
    this.dataSize-=this.entries.get(id)?.data?.length??0;
    this.entries.delete(id);this.entries.set(id,{...entry,threadId});this.dataSize+=entry.data?.length??0;
    while(this.entries.size>512||this.dataSize>Math.ceil(32*1024*1024*4/3)){
      const oldest=this.entries.keys().next().value;this.dataSize-=this.entries.get(oldest)?.data?.length??0;this.entries.delete(oldest);
    }
    return{id,name:entry.name};
  }
  decorate(threadId,snapshot){
    const result=structuredClone(snapshot),cwd=result.thread?.cwd;
    const rewrite=text=>{
      if(typeof text!=='string')return text;
      let replaced=text;
      for(const reference of markdownImages(text).reverse()){
        const image=this.register(threadId,reference.source,cwd,reference.name);
        if(image)replaced=replaced.slice(0,reference.start)+`/api/images/${image.id}`+replaced.slice(reference.end);
      }
      return replaced;
    };
    for(const turn of result.turns??[])for(const item of turn.items??[]){
      if(item.type==='agentMessage')item.text=rewrite(item.text);
      if(item.type==='userMessage')for(const part of item.content??[]){
        if(part.type==='text')part.text=rewrite(part.text);
        else if(['localImage','image','inputImage'].includes(part.type)){
          const source=part.path??part.url??part.imageUrl??part.image_url;
          const image=this.register(threadId,source,cwd);
          if(image){part.imageId=image.id;part.name=image.name;}
        }
      }
    }
    return result;
  }
  async read(id){
    const entry=this.entries.get(id);if(!entry)throw fail(404,'图片暂不可用，请刷新会话后重试');
    let bytes;
    if(entry.data){
      if(!/^[A-Za-z0-9+/=\r\n]+$/.test(entry.data))throw fail(415,'图片数据无效');
      bytes=Buffer.from(entry.data,'base64');
    }else{
      let handle;
      try{
        handle=await open(entry.file,'r');const stat=await handle.stat();
        if(!stat.isFile())throw fail(415,'此附件不是图片文件');
        if(stat.size>MAX_IMAGE_BYTES)throw fail(413,'图片超过 20 MB，请在电脑查看');
        bytes=Buffer.alloc(stat.size);let offset=0;
        while(offset<bytes.length){const {bytesRead}=await handle.read(bytes,offset,bytes.length-offset,offset);if(!bytesRead)break;offset+=bytesRead;}
        bytes=bytes.subarray(0,offset);
      }catch(error){
        if(error.code==='ENOENT')throw fail(404,'电脑上的图片已被移动或删除');
        if(error.code==='EACCES'||error.code==='EPERM')throw fail(403,'电脑无法读取此图片');
        throw error;
      }finally{await handle?.close();}
    }
    if(bytes.length>MAX_IMAGE_BYTES)throw fail(413,'图片超过 20 MB，请在电脑查看');
    return{bytes,mime:imageMime(bytes)};
  }
}
