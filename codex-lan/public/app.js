const $=id=>document.getElementById(id);
let csrf='',threads=[],selected='',cursor=null,older=[],snapshot='',busy=false,sending=false,timer,setupInfo,loadedOlder=false;
let generation=0,wasOffline=false;
const drafts=new Map();
async function api(url,options={}) {
  const response=await fetch(url,{signal:AbortSignal.timeout(options.method==='POST'?75000:25000),...options,headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{}),...options.headers}});
  const result=await response.json();
  if(!response.ok){const error=new Error(result.error??'连接失败');error.status=response.status;error.state=result.state;throw error;}
  return result;
}
const post=(url,body)=>api(url,{method:'POST',body:JSON.stringify(body)});
function notice(text,error=false){$('notice').textContent=text;$('notice').hidden=!text;$('notice').classList.toggle('error-notice',error);}
function connection(ok){$('connection').classList.toggle('offline',!ok);$('connection-text').textContent=ok?'桌面已连接':'连接中断';}
function sidebar(show){$('sidebar').classList.toggle('open',show);$('backdrop').hidden=!show;}
function title(thread){return thread.title||thread.preview||'未命名聊天';}
function renderList(){
  $('thread-list').replaceChildren();
  const query=$('search').value.toLowerCase();
  for(const thread of threads.filter(t=>title(t).toLowerCase().includes(query))){
    const button=document.createElement('button');button.className=`thread${thread.id===selected?' selected':''}`;
    const name=document.createElement('span');name.className='thread-name';name.textContent=title(thread);
    const meta=document.createElement('span');meta.className='thread-info';
    const status=typeof thread.status==='string'?thread.status:thread.status?.type;
    meta.textContent=`${['active','inProgress','running'].includes(status)?'● 正在执行 · ':''}${thread.projectName||thread.cwd?.split(/[\\/]/).filter(Boolean).at(-1)||'本机聊天'}`;
    button.append(name,meta);button.onclick=()=>selectThread(thread.id);$('thread-list').append(button);
  }
  if(!threads.length){const empty=document.createElement('p');empty.className='muted small';empty.textContent='尚未读到本机聊天。检查电脑的连接来源设置。';$('thread-list').append(empty);}
}
function appendText(element,text){
  function inline(parent,value){
    const pattern=/(`[^`\n]+`|\*\*[^*\n]+\*\*|\[[^\]\n]+\]\(https?:\/\/[^\s)]+\))/g;
    let end=0;
    for(const match of value.matchAll(pattern)){
      parent.append(document.createTextNode(value.slice(end,match.index)));end=match.index+match[0].length;
      const token=match[0];let node;
      if(token.startsWith('`')){node=document.createElement('code');node.textContent=token.slice(1,-1);}
      else if(token.startsWith('**')){node=document.createElement('strong');node.textContent=token.slice(2,-2);}
      else{const link=token.match(/^\[([^\]]+)\]\((.+)\)$/);node=document.createElement('a');node.textContent=link[1];node.href=link[2];node.target='_blank';node.rel='noopener noreferrer';}
      parent.append(node);
    }
    parent.append(document.createTextNode(value.slice(end)));
  }
  const parts=String(text??'').split(/```[^\n]*\n([\s\S]*?)```/g);
  parts.forEach((part,index)=>{
    if(index%2){const pre=document.createElement('pre');pre.textContent=part;element.append(pre);return;}
    for(const line of part.split('\n')){
      const heading=line.match(/^ {0,3}(#{1,6})\s+(.+?)\s*#*$/),bullet=line.match(/^\s*[-*+]\s+(.+)$/);
      const row=document.createElement(heading?`h${heading[1].length}`:'div');
      inline(row,heading?heading[2]:bullet?'• '+bullet[1]:line||'\u00a0');element.append(row);
    }
  });
}
function userContent(raw){
  const text=String(raw??'');
  if(text.trimStart().startsWith('<codex_delegation>')){const input=text.match(/<input>([\s\S]*)<\/input>/);if(input)return{text:input[1].trim(),context:text};}
  const marker=/^## My request:\s*\n/m.exec(text);
  if(marker&&/^\s*(<in-app-browser-context|# Files mentioned by the user:|<external_context)/.test(text))return{text:text.slice(marker.index+marker[0].length).trim(),context:text.slice(0,marker.index).trim()};
  if(text.startsWith('# AGENTS.md instructions')&&text.includes('<INSTRUCTIONS>'))return{text:'会话配置',context:text};
  return{text,context:null};
}
function renderTurns(turns){
  const viewport=$('conversation'),atBottom=viewport.scrollHeight-viewport.scrollTop-viewport.clientHeight<130;
  const previousHeight=viewport.scrollHeight,previousTop=viewport.scrollTop;
  const fragment=document.createDocumentFragment();
  for(const turn of turns){
    let activityGroup=null,activityLabel=null,activityCount=0;
    for(const item of turn.items??[]){
      if(item.type==='userMessage'||item.type==='agentMessage'){
        activityGroup=null;activityLabel=null;activityCount=0;
        const box=document.createElement('article');box.className=`message ${item.type==='userMessage'?'user':item.phase==='commentary'?'commentary':'agent'}`;
        const label=document.createElement('div');label.className='message-label';label.textContent=item.type==='userMessage'?'你':item.phase==='commentary'?'Codex · 进度':'Codex';
        const content=document.createElement('div');content.className='message-content';
        const text=item.type==='userMessage'?(item.content??[]).map(p=>p.type==='text'?p.text:p.type==='image'?'[图片]':'').join('\n'):item.text;
        const visible=item.type==='userMessage'?userContent(text):{text};
        appendText(content,visible.text||'[附件]');box.append(label,content);fragment.append(box);
        if(visible.context){const details=document.createElement('details');details.className='activity-group';const summary=document.createElement('summary');summary.textContent='会话上下文';const pre=document.createElement('pre');pre.textContent=visible.context;details.append(summary,pre);fragment.append(details);}
      }else if(['commandExecution','fileChange','mcpToolCall','dynamicToolCall','webSearch'].includes(item.type)){
        if(!activityGroup){activityGroup=document.createElement('details');activityGroup.className='activity-group';activityLabel=document.createElement('summary');activityGroup.append(activityLabel);fragment.append(activityGroup);}
        activityCount++;activityLabel.textContent=`执行记录 · ${activityCount} 项`;
        const details=document.createElement('details');details.className='activity';
        const summary=document.createElement('summary');
        const status=item.status==='completed'?'✓':item.status==='failed'?'!':'◌';
        const names={commandExecution:'执行命令',fileChange:'修改文件',mcpToolCall:'调用工具',dynamicToolCall:'调用工具',webSearch:'搜索资料'};
        summary.textContent=`${status} ${names[item.type]}${item.tool?` · ${item.tool}`:''}`;
        const pre=document.createElement('pre');
        pre.textContent=[item.command,item.aggregatedOutput,...(item.changes??[]).map(c=>c.path),item.error?JSON.stringify(item.error):''].filter(Boolean).join('\n')||JSON.stringify(item,null,2);
        details.append(summary,pre);activityGroup.append(details);
      }
    }
    if(turn.status&&turn.status!=='inProgress'){
      const state=document.createElement('div');state.className='turn-end';state.textContent=({completed:'本轮完成',interrupted:'本轮已中断',failed:'本轮执行失败'})[turn.status]??turn.status;fragment.append(state);
      if(turn.error){const error=document.createElement('p');error.className='error';error.textContent=turn.error.message??JSON.stringify(turn.error);fragment.append(error);}
    }
  }
  $('messages').replaceChildren(fragment);
  if(atBottom){viewport.scrollTop=viewport.scrollHeight;$('new-updates').hidden=true;}
  else{viewport.scrollTop=previousTop; if(viewport.scrollHeight>previousHeight)$('new-updates').hidden=false;}
}
async function selectThread(id){
  if(selected)drafts.set(selected,$('prompt').value);
  selected=id;generation++;older=[];snapshot='';cursor=null;loadedOlder=false;renderList();sidebar(false);
  sessionStorage.setItem('selectedThread',id);$('messages').replaceChildren();$('empty').hidden=true;$('load-older').hidden=true;
  $('thread-title').textContent=title(threads.find(t=>t.id===id)??{});$('thread-meta').textContent=`本机 · ${id.slice(0,8)}`;
  $('prompt').value=drafts.get(id)??'';$('prompt').disabled=sending;$('send').disabled=sending;
  await refresh();
}
async function refresh(){
  if(busy||document.hidden)return;
  if(!selected){if(csrf){busy=true;try{await boot();}finally{busy=false;}}return;}
  const id=selected,g=generation;busy=true;
  try{
    const result=await api(`/api/threads/${id}`);
    if(g!==generation)return;
    connection(true);if(wasOffline){notice('连接已恢复');wasOffline=false;}
    const data=result.turns??[];
    const merged=new Map([...older,...data].map(t=>[t.id,t]));
    // read_thread returns newest-first pages; preserve ordering across pages.
    const turns=[...merged.values()].sort((a,b)=>(a.startedAt??0)-(b.startedAt??0));
    const serialized=JSON.stringify(turns);
    if(snapshot!==serialized){renderTurns(turns);snapshot=serialized;}
    if(!loadedOlder)cursor=result.page?.nextCursor??null;$('load-older').hidden=!cursor;
    const status=result.thread?.status?.type;
    $('compose-status').textContent=status==='active'?'正在执行 · 消息将发送给同一会话':'自动刷新 · 同一个会话';
    $('thread-title').textContent=result.thread?.title||$('thread-title').textContent;
  }catch(error){
    if(g!==generation)return;
    if(error.status===401)return showPair();
    connection(false);wasOffline=true;notice(error.message,true);
  }finally{busy=false; if(g!==generation)refresh();}
}
async function boot(){
  $('pair-screen').hidden=true;$('workspace').hidden=false;
  try{
    await api('/api/status');connection(true);
    const result=await api('/api/threads');threads=result.threads;renderList();
    const previous=sessionStorage.getItem('selectedThread');
    if(threads.some(t=>t.id===previous))await selectThread(previous);
    else if(threads.length)await selectThread(threads.find(t=>t.status?.type==='active'||t.status==='running')?.id??threads[0].id);
  }catch(error){connection(false);notice(error.message,true);}
  clearInterval(timer);timer=setInterval(refresh,1200);
}
function showPair(){clearInterval(timer);$('pair-screen').hidden=false;$('workspace').hidden=true;csrf='';}
$('pair-form').onsubmit=async event=>{
  event.preventDefault();$('pair-error').textContent='';const button=event.submitter;button.disabled=true;
  try{const result=await post('/api/pair',{code:$('code').value});csrf=result.csrf;
    if(setupInfo&&$('caller-id').value.trim())await post('/api/connection',{threadId:$('caller-id').value.trim()});
    await boot();
  }catch(error){$('pair-error').textContent=error.message;}finally{button.disabled=false;}
};
$('save-caller').onclick=async()=>{
  try{if(!csrf){const result=await post('/api/pair',{code:setupInfo.pairingCode});csrf=result.csrf;}
    await post('/api/connection',{threadId:$('caller-id').value.trim()});$('pair-error').textContent='连接来源已保存，重启后仍有效。';
  }catch(error){$('pair-error').textContent=error.message;}
};
$('composer').onsubmit=async event=>{
  event.preventDefault();if(sending||!selected)return;
  const prompt=$('prompt').value.trim();if(!prompt)return;
  const id=selected,g=generation,requestId=crypto.randomUUID();sending=true;$('send').disabled=true;$('prompt').disabled=true;
  notice('正在提交到桌面聊天…');
  try{
    const result=await post(`/api/threads/${id}/messages`,{prompt,requestId});
    if(result.state!=='sent')throw new Error('发送结果尚未确认');
    drafts.delete(id);if(g===generation)$('prompt').value='';notice('已提交到桌面会话');await refresh();
  }catch(error){notice(`${error.message}\n请核对桌面聊天，确认后再发送。`,true);}
  finally{sending=false;$('send').disabled=!selected;$('prompt').disabled=!selected;}
};
$('load-older').onclick=async()=>{
  if(!cursor||!selected)return;const g=generation,viewport=$('conversation'),oldHeight=viewport.scrollHeight,oldTop=viewport.scrollTop;
  $('load-older').disabled=true;
  try{const result=await api(`/api/threads/${selected}?cursor=${encodeURIComponent(cursor)}`);if(g!==generation)return;
    older.push(...(result.turns??[]));cursor=result.page?.nextCursor??null;loadedOlder=true;
    // Preserve the newest page and the older pages together.
    const current=JSON.parse(snapshot||'[]');const merged=new Map([...older,...current].map(t=>[t.id,t]));
    const turns=[...merged.values()].sort((a,b)=>(a.startedAt??0)-(b.startedAt??0));older=turns;renderTurns(turns);snapshot=JSON.stringify(turns);
    viewport.scrollTop=oldTop+viewport.scrollHeight-oldHeight;$('load-older').hidden=!cursor;
  }catch(error){notice(error.message,true);}finally{$('load-older').disabled=false;}
};
$('logout').onclick=async()=>{try{await post('/api/logout',{});}finally{showPair();}};
$('search').oninput=renderList;$('open-sidebar').onclick=()=>sidebar(true);$('close-sidebar').onclick=()=>sidebar(false);$('backdrop').onclick=()=>sidebar(false);
$('jump-latest').onclick=()=>{$('conversation').scrollTop=$('conversation').scrollHeight;$('new-updates').hidden=true;};
document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
window.addEventListener('online',refresh);
async function openSettings(){
  try{setupInfo=await api('/api/local-info');$('new-pairing-code').value=setupInfo.pairingCode;$('settings-result').textContent='';$('connection-settings').showModal();}
  catch(error){notice(error.message,true);}
}
async function changePairingCode(body){
  try{
    if(!csrf){const session=await post('/api/pair',{code:setupInfo.pairingCode});csrf=session.csrf;}
    const result=await post('/api/settings/pairing-code',body);
    setupInfo.pairingCode=result.pairingCode;$('local-code').textContent=result.pairingCode;$('new-pairing-code').value=result.pairingCode;
    $('settings-result').textContent='配对码已保存。已配对设备保持连接。';
  }catch(error){$('settings-result').textContent=error.message;if(error.status===401)csrf='';}
}
$('pairing-settings-form').onsubmit=event=>{event.preventDefault();changePairingCode({code:$('new-pairing-code').value});};
$('random-pairing-code').onclick=()=>changePairingCode({random:true});
$('close-connection-settings').onclick=()=>$('connection-settings').close();
try{
  if(['localhost','127.0.0.1','[::1]'].includes(location.hostname)){
    setupInfo=await api('/api/local-info');$('setup').hidden=false;$('local-code').textContent=setupInfo.pairingCode;$('caller-id').value=setupInfo.callerThreadId;
    for(const parent of [$('setup'),document.querySelector('.sidebar-bottom')]){const button=document.createElement('button');button.type='button';button.className='secondary';button.textContent='连接设置';button.onclick=openSettings;parent.append(button);}
    for(const url of setupInfo.urls){const link=document.createElement('a');link.className='url';link.href=url;link.textContent=url;$('local-urls').append(link);}
  }
  const session=await api('/api/session');csrf=session.csrf;await boot();
}catch(error){if(error.status!==401)$('pair-error').textContent=error.message;}
