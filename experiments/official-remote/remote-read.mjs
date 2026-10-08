import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { keyOperation } from './device-key.mjs';
import { connectionPayload, signedBytes } from './auth-protocol.mjs';
import { WireWebSocket } from './wire-websocket.mjs';

export async function testRemoteRead({ authHeaders, key, session, record }) {
  const threadId = process.env.REMOTE_PROBE_THREAD_ID;
  if (!threadId) throw new Error('Select the original Desktop chat with REMOTE_PROBE_THREAD_ID');
  const state = JSON.parse(await readFile(path.join(process.env.USERPROFILE, '.codex', '.codex-global-state.json'), 'utf8'));
  const envId = state['electron-local-remote-control-environment-id'];
  if (typeof envId !== 'string') throw new Error('Desktop host identity unavailable');
  const response = await fetch(`https://chatgpt.com/backend-api/codex/remote/control/clients/${encodeURIComponent(key.clientId)}/environments?limit=50`, {
    headers: authHeaders, signal: AbortSignal.timeout(15000), redirect: 'error',
  });
  if (!response.ok) { await response.body?.cancel(); await record({ stage: 'client-host-list', status: response.status }); throw new Error('Client host authorization could not be read'); }
  const environments = await response.json();
  const authorized = environments.items?.some(item => item.env_id === envId) === true;
  await record({ stage: 'client-host-authorization', hostPaired: authorized });
  const wire = await WireWebSocket.connect({ ...authHeaders, 'x-codex-client-id': key.clientId, 'x-codex-protocol-version': '3', 'x-codex-client-session-token': `Bearer ${session.remote_control_token}` });
  const streamId = randomUUID(); let seq = 1;
  const envelope = message => ({ type: 'client_message', client_id: key.clientId, stream_id: streamId, env_id: envId, seq_id: seq++, skip_history: false, message });
  try {
    const challenge = await wire.next();
    await record({ stage: 'websocket-challenge-schema', fields: Object.keys(challenge).sort() });
    const payload = signedBytes(connectionPayload(challenge, key, session));
    const signature = await keyOperation({ operation: 'sign', keyId: key.keyId, payloadBase64: payload.toString('base64') });
    wire.send({ type: 'device_key_proof', keyId: key.keyId, signatureDerBase64: signature.signatureDerBase64, signedPayloadBase64: payload.toString('base64'), algorithm: signature.algorithm });
    await record({ stage: 'websocket-upgrade-and-proof', upgraded: true, proofSent: true, proofAccepted: 'pending' });
    if (!authorized) {
      await record({ stage: 'host-pairing-required', originalHostFound: true, chatRead: false });
      return;
    }
    const chunks = new Map();
    async function rpc(message, expectedId) {
      wire.send(envelope(message));
      if (expectedId === undefined) return;
      const deadline = Date.now() + 20000;
      while (Date.now() < deadline) {
        const item = await wire.next(Math.max(1, deadline - Date.now()));
        if (item.client_id !== key.clientId || item.env_id !== envId || item.stream_id !== streamId) continue;
        let reply = item.message;
        if (item.type === 'server_message_chunk') {
          if (!Number.isInteger(item.segment_count) || item.segment_count < 1 || item.segment_count > 170 || item.message_size_bytes > 16 * 1024 * 1024 || item.segment_id < 0 || item.segment_id >= item.segment_count) throw new Error('Invalid remote response chunk');
          let assembly = chunks.get(item.seq_id); if (!assembly) { assembly = { parts: new Map(), count: item.segment_count }; chunks.set(item.seq_id, assembly); }
          assembly.parts.set(item.segment_id, Buffer.from(item.message_chunk_base64, 'base64'));
          if (assembly.parts.size !== assembly.count) continue;
          const bytes = Buffer.concat(Array.from({ length: assembly.count }, (_, index) => assembly.parts.get(index)));
          if (bytes.length !== item.message_size_bytes) throw new Error('Remote response size mismatch');
          reply = JSON.parse(bytes.toString()); chunks.delete(item.seq_id);
        }
        if (reply?.id !== expectedId) continue;
        if (reply.error) { await record({ stage: `rpc-${expectedId}`, errorCode: reply.error.code }); throw new Error('Remote app-server returned an RPC error'); }
        return reply.result;
      }
      throw new Error('Remote RPC timed out');
    }
    await rpc({ id: 'probe-initialize', method: 'initialize', params: { clientInfo: { name: 'codex_light_remote_probe', version: '0.1.0' }, capabilities: { experimentalApi: true } } }, 'probe-initialize');
    await rpc({ method: 'initialized' });
    await record({ stage: 'remote-app-server-initialized', proofAccepted: true });
    const result = await rpc({ id: 'probe-original-thread-read', method: 'thread/read', params: { threadId, includeTurns: false } }, 'probe-original-thread-read');
    await record({ stage: 'original-thread-read', matchingThread: result.thread?.id === threadId, status: result.thread?.status?.type });
    wire.send({ type: 'client_closed', client_id: key.clientId, stream_id: streamId, env_id: envId, seq_id: seq++ });
  } finally { wire.close(); }
}
