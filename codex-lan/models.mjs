export function modelCatalog(tool) {
  const schema = tool?.inputSchema?.properties;
  const allowed = new Set(schema?.thinking?.enum ?? []);
  const models = [...(schema?.model?.description ?? '').matchAll(/([a-zA-Z0-9][a-zA-Z0-9._-]{0,80}) \([^()]*?supported reasoning efforts: ([a-z, ]+)\)/g)]
    .map(match => ({id: match[1], efforts: match[2].split(',').map(value => value.trim()).filter(value => allowed.has(value))}))
    .filter(model => model.efforts.length);
  return {models: [...new Map(models.map(model => [model.id, model])).values()]};
}

export function selectedModel(body, catalog) {
  const model = body.model, thinking = body.thinking;
  if (model === undefined && thinking === undefined) return {};
  const entry = catalog.models.find(entry => entry.id === model);
  if (!entry || (thinking !== undefined && !entry.efforts.includes(thinking))) {
    throw Object.assign(new Error('模型或推理强度已不可用，请刷新模型列表重新选择'), {status: 400});
  }
  return {model, ...(thinking === undefined ? {} : {thinking})};
}
