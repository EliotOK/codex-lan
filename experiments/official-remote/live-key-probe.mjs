import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createPublicKey, verify } from 'node:crypto';
import { randomUUID } from 'node:crypto';
import { keyOperation } from './device-key.mjs';
import { ORIGIN, backendIdentity, validateEnrollmentAccount, enrollmentPayload, signedBytes } from './auth-protocol.mjs';

const auth = JSON.parse(await readFile(path.join(process.env.USERPROFILE, '.codex', 'auth.json'), 'utf8'));
const identity = backendIdentity(auth.tokens.access_token, auth.tokens.account_id);
const report = { checkedAt: new Date().toISOString(), stages: [] };
let key;
const record = data => report.stages.push(data);
try {
  const response = await fetch(new URL('/backend-api/codex/remote/control/client/enroll/start', ORIGIN), {
    method: 'POST', headers: { Authorization: `Bearer ${auth.tokens.access_token}`,
      'ChatGPT-Account-ID': auth.tokens.account_id, originator: 'codex_light_remote_probe',
      'Content-Type': 'application/json' }, body: '{}', redirect: 'error', signal: AbortSignal.timeout(15000),
  });
  record({ stage: 'enroll/start', status: response.status });
  if (!response.ok) { await response.body?.cancel(); throw new Error('Enrollment start failed'); }
  const enrollment = await response.json();
  validateEnrollmentAccount(enrollment, identity);
  record({ stage: 'current-account-binding', passed: true,
    legacyIdentity: enrollment.account_user_id !== identity.accountUserId });
  key = { ...await keyOperation({ operation: 'create' }),
    accountUserId: enrollment.account_user_id, clientId: enrollment.client_id };
  const expires = enrollment.device_key_challenge.challenge_expires_at;
  record({ stage: 'challenge-expiry-format', valueType: typeof expires,
    futureUnixSeconds: Number.isSafeInteger(expires) && expires * 1000 > Date.now(),
    futureIsoDate: typeof expires === 'string' && Date.parse(expires) > Date.now() });
  const payload = signedBytes(enrollmentPayload(enrollment.device_key_challenge, key,
    '/backend-api/codex/remote/control/client/enroll/finish'));
  record({ stage: 'live-challenge-binding', passed: true });
  const signature = await keyOperation({ operation: 'sign', keyId: key.keyId, payloadBase64: payload.toString('base64') });
  const publicKey = createPublicKey({ key: Buffer.from(key.publicKeySpkiDerBase64, 'base64'), format: 'der', type: 'spki' });
  const passed = verify('sha256', payload, publicKey, Buffer.from(signature.signatureDerBase64, 'base64'));
  record({ stage: 'live-challenge-signature', locallyVerified: passed, submittedToServer: false });
  if (!passed) throw new Error('Device signature verification failed');
} catch (error) {
  record({ stage: 'failed', failure: error.message }); process.exitCode = 1;
} finally {
  if (key) {
    try { await keyOperation({ operation: 'delete', keyId: key.keyId }); record({ stage: 'test-key-cleanup', deleted: true }); }
    catch { record({ stage: 'test-key-cleanup', deleted: false }); process.exitCode = 1; }
  }
  const json = JSON.stringify(report, null, 2);
  await writeFile(new URL(`./live-key-attempt-${randomUUID()}.json`, import.meta.url), json);
  await writeFile(new URL('./live-key-results.json', import.meta.url), json);
  console.log(JSON.stringify(report));
}
