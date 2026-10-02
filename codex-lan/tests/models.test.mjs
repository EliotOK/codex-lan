import {test} from 'node:test';
import assert from 'node:assert/strict';
import {modelCatalog,selectedModel} from '../models.mjs';
test('model catalog reads advertised per-model efforts and omits unknown schema values',()=>{
 const result=modelCatalog({inputSchema:{properties:{model:{description:'Models: gpt-6-luna (Fast model.; supported reasoning efforts: low, high, max), gpt-6-astra (Model.; supported reasoning efforts: high, ultra, fake).'},thinking:{enum:['low','high','max','ultra']}}}});
 assert.deepEqual(result.models,[{id:'gpt-6-luna',efforts:['low','high','max']},{id:'gpt-6-astra',efforts:['high','ultra']}]);
 assert.deepEqual(selectedModel({},result),{});
 assert.deepEqual(selectedModel({model:'gpt-6-luna',thinking:'max'},result),{model:'gpt-6-luna',thinking:'max'});
 assert.throws(()=>selectedModel({model:'gpt-6-luna',thinking:'ultra'},result),{status:400});
 assert.throws(()=>selectedModel({thinking:'high'},result),{status:400});
 assert.deepEqual(modelCatalog({}).models,[]);
});
