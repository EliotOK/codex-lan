import path from 'node:path';
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { keyOperation } from './device-key.mjs';
import { ORIGIN, WS_SCOPE, backendIdentity, validateEnrollmentAccount, enrollmentPayload, signedBytes } from './auth-protocol.mjs';
import { testRemoteRead } from './remote-read.mjs';

if (!process.env.REMOTE_PROBE_THREAD_ID) throw new Error('Set REMOTE_PROBE_THREAD_ID before resuming the test');
const auth = JSON.parse(await readFile(path.join(process.env.USERPROFILE, '.codex', 'auth.json'), 'utf8'));
if (!auth.tokens?.access_token || !auth.tokens.account_id) throw new Error('ChatGPT login is required');
const authHeaders = { Authorization: `Bearer ${auth.tokens.access_token}`, 'ChatGPT-Account-ID': auth.tokens.account_id,
  'Content-Type': 'application/json', originator: 'codex_light_remote_probe' };
const report = { checkedAt: new Date().toISOString(), stages: [] };
const attemptFile = new URL(`./resume-attempt-${randomUUID()}.json`, import.meta.url);
const record = async data => {
  report.stages.push(data); console.log(JSON.stringify(data));
  const json = JSON.stringify(report, null, 2);
  await writeFile(attemptFile, json); await writeFile(new URL('./resume-results.json', import.meta.url), json);
};
async function post(endpoint, body) {
  const response = await fetch(new URL(endpoint, ORIGIN), { method: 'POST', headers: authHeaders,
    body: JSON.stringify(body), signal: AbortSignal.timeout(15000), redirect: 'error' });
  await record({ stage: endpoint.split('/').slice(-2).join('/'), status: response.status });
  if (!response.ok) { await response.body?.cancel(); throw new Error(`Official endpoint returned HTTP ${response.status}`); }
  return await response.json();
}
async function readPairingCode() {
  const parts = []; let size = 0;
  for await (const chunk of process.stdin) {
    size += chunk.length;
    if (size > 256) throw new Error('Pairing input is too large');
    parts.push(chunk);
  }
  const code = Buffer.concat(parts).toString('utf8').trim();
  if (!/^[A-Za-z0-9-]{8,64}$/.test(code)) throw new Error('Invalid manual pairing code');
  return code;
}
try {
  const reference = JSON.parse(await readFile(new URL('./test-key-reference.json', import.meta.url), 'utf8'));
  if (!reference.clientId || !reference.accountUserId) throw new Error('Complete enrolled device reference is required');
  const key = { ...await keyOperation({ operation: 'read-public', keyId: reference.keyId }),
    clientId: reference.clientId, accountUserId: reference.accountUserId };
  validateEnrollmentAccount({ account_user_id: key.accountUserId }, backendIdentity(auth.tokens.access_token, auth.tokens.account_id));
  const challenge = await post('/backend-api/codex/remote/control/client/refresh/start', { client_id: key.clientId });
  validateEnrollmentAccount(challenge, backendIdentity(auth.tokens.access_token, auth.tokens.account_id));
  const payload = signedBytes(enrollmentPayload(challenge.device_key_challenge, key,
    '/backend-api/codex/remote/control/client/refresh/finish', { refresh: true }));
  const signature = await keyOperation({ operation: 'sign', keyId: key.keyId, payloadBase64: payload.toString('base64') });
  const session = await post('/backend-api/codex/remote/control/client/refresh/finish', {
    client_id: key.clientId, device_key_proof: { challenge_token: challenge.device_key_challenge.challenge_token,
      key_id: key.keyId, signature_der_base64: signature.signatureDerBase64,
      signed_payload_base64: payload.toString('base64'), algorithm: signature.algorithm },
  });
  if (session.client_id !== key.clientId || session.account_user_id !== key.accountUserId
      || session.scopes?.length !== 1 || session.scopes[0] !== WS_SCOPE || !(Date.parse(session.expires_at) > Date.now())) {
    throw new Error('Refreshed session metadata mismatch');
  }
  await record({ stage: 'session-refreshed', loginRepeated: false, sessionVerified: true });
  if (process.argv.includes('--pair')) {
    await post('/backend-api/codex/remote/control/client/pair', {
      client_id: key.clientId, manual_pairing_code: await readPairingCode(),
    });
    await record({ stage: 'manual-pairing-submitted', originalHostVerified: 'pending' });
  }
  const result = await testRemoteRead({ authHeaders, key, session, record });
  await record({ stage: result.stage });
} catch (error) {
  await record({ stage: 'failed', failure: error.message }); process.exitCode = 1;
}
