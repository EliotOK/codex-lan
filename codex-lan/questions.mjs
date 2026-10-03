const fail=message=>{throw Object.assign(new Error(message),{status:400});};
export function questionCards(state){return (state.requests??[]).filter(r=>r.method==='item/tool/requestUserInput'||r.method==='mcpServer/elicitation/request').map(r=>{
  const p=r.params??{};
  return {id:r.id,kind:r.method==='item/tool/requestUserInput'?'plan':'mcp',turnId:p.turnId??null,questions:p.questions??[],message:p.message??'',serverName:p.serverName??'',mode:p.mode??'form',schema:p.requestedSchema??null};
});}
export function validateAnswer(request,response){
  if(!response||typeof response!=='object'||Array.isArray(response))fail('回答格式无效');
  if(request.method==='item/tool/requestUserInput'){
    const answers=response.answers,questions=request.params.questions;
    if(!answers||Object.keys(answers).length!==questions.length)fail('请回答所有问题');
    for(const q of questions){const values=answers[q.id]?.answers;if(!Array.isArray(values)||values.length!==1||typeof values[0]!=='string'||!values[0].trim()||values[0].length>10000)fail('请填写有效答案');
      if(q.options?.length&&!q.isOther&&!q.options.some(o=>o.label===values[0]))fail('请选择有效选项');
    }return {answers:Object.fromEntries(questions.map(q=>[q.id,{answers:answers[q.id].answers}]))};
  }
  if(!['form','openai/form'].includes(request.params.mode??'form'))fail('请在电脑完成此类提问');
  if(!['accept','decline','cancel'].includes(response.action))fail('请选择有效操作');
  if(response.action!=='accept')return {action:response.action,content:null};
  const schema=request.params.requestedSchema??{},content=response.content;
  if(!content||typeof content!=='object'||Array.isArray(content))fail('表单回答格式无效');
  for(const name of schema.required??[])if(!Object.hasOwn(content,name))fail('请填写必填项');
  for(const [name,value]of Object.entries(content)){const field=schema.properties?.[name];if(!field)fail('表单包含未知字段');
    if(field.type==='string'){if(typeof value!=='string'||value.length>10000||((schema.required??[]).includes(name)&&!value.trim())||(field.minLength!=null&&value.length<field.minLength)||(field.maxLength!=null&&value.length>field.maxLength))fail('文字字段无效');}
    else if(field.type==='boolean'){if(typeof value!=='boolean')fail('确认字段无效');}
    else if(field.type==='number'||field.type==='integer'){if(typeof value!=='number'||!Number.isFinite(value)||(field.type==='integer'&&!Number.isInteger(value))||(field.minimum!=null&&value<field.minimum)||(field.maximum!=null&&value>field.maximum))fail('数值字段无效');}
    else if(field.type==='array'&&field.items?.type==='string'){if(!Array.isArray(value)||new Set(value).size!==value.length||value.some(v=>typeof v!=='string'||(field.items.enum&&!field.items.enum.includes(v)))||(field.minItems!=null&&value.length<field.minItems)||(field.maxItems!=null&&value.length>field.maxItems))fail('多选字段无效');}
    else fail('此类表单请在电脑填写');
    if(field.enum&&!field.enum.includes(value))fail('请选择有效选项');
  }return {action:'accept',content};
}
