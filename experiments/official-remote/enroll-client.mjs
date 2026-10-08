import http from 'node:http';
import path from 'node:path';
import { readFile, writeFile } from 'node:fs/promises';
import { randomBytes, randomUUID } from 'node:crypto';
import { keyOperation } from './device-key.mjs';
import { ORIGIN, ENROLL_SCOPE, digest, enrollmentPayload, signedBytes, validateStepUp, WS_SCOPE, backendIdentity, validateEnrollmentAccount } from './auth-protocol.mjs';

const auth = JSON.parse(await readFile(path.join(process.env.USERPROFILE, '.codex', 'auth.json'), 'utf8'));
if (!auth.tokens?.access_token || !auth.tokens.account_id) throw new Error('ChatGPT login is required');
const authIdentity = backendIdentity(auth.tokens.access_token, auth.tokens.account_id);
if (!process.env.REMOTE_PROBE_THREAD_ID) throw new Error('Set REMOTE_PROBE_THREAD_ID before starting the authorization test');
const authHeaders = { Authorization: `Bearer ${auth.tokens.access_token}`, 'ChatGPT-Account-ID': auth.tokens.account_id, originator: 'codex_light_remote_probe', 'Content-Type': 'application/json' };
const clientId = 'app_EMoamEEZ73f0CkXaXp7hrann';
const verifier = randomBytes(32).toString('base64url');
const state = randomBytes(32).toString('base64url');
const pageKey = randomBytes(24).toString('base64url');
const report = { checkedAt: new Date().toISOString(), stages: [] };
const attemptFile = new URL(`./enrollment-attempt-${randomUUID()}.json`, import.meta.url);
let stage = 'awaiting-user-authentication', port, server, key, session, finished = false, redirectUri;
let authorizationStarted = false, callbackClaimed = false;
const deadline = Date.now() + 10 * 60 * 1000;
const record = async data => { report.stages.push(data); console.log(JSON.stringify(data)); const json = JSON.stringify(report, null, 2); await writeFile(attemptFile, json); await writeFile(new URL('./enrollment-results.json', import.meta.url), json); };
const html = message => `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Codex Light · 中继测试</title><style>body{background:#181818;color:#eee;font:18px/1.7 system-ui;max-width:680px;padding:32px;margin:auto}a{color:#94baff}button,a.button{display:inline-block;background:#3266c9;border:0;border-radius:14px;color:white;padding:12px 20px;text-decoration:none}small{color:#aaa}</style><h1>官方中继兼容测试</h1>${message}`;
function respond(res, status, text, type = 'text/html; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'none'; frame-ancestors 'none'" }); res.end(text);
}
async function stop(reason) {
  if (finished) return; finished = true; clearTimeout(expiry);
  if (key && !session) { try { await keyOperation({ operation: 'delete', keyId: key.keyId }); await record({ stage: 'test-key-cleanup', deleted: true }); } catch { await record({ stage: 'test-key-cleanup', deleted: false }); } }
  server?.close(); server?.closeAllConnections();
  await record({ stage: 'stopped', reason });
}
async function post(endpoint, body) {
  const response = await fetch(new URL(endpoint, ORIGIN), { method: 'POST', headers: authHeaders, body: JSON.stringify(body), signal: AbortSignal.timeout(15000), redirect: 'error' });
  await record({ stage: endpoint.split('/').slice(-2).join('/'), status: response.status });
  if (!response.ok) { await response.body?.cancel(); throw new Error(`Official endpoint returned HTTP ${response.status}`); }
  return await response.json();
}
async function finishEnrollment(code) {
  stage = 'exchanging-step-up-code';
  const response = await fetch('https://auth.openai.com/oauth/token', { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000), headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'authorization_code', code, redirect_uri: redirectUri, client_id: clientId, code_verifier: verifier }) });
  await record({ stage: 'step-up-token-exchange', status: response.status });
  if (!response.ok) { await response.body?.cancel(); throw new Error(`Step-up exchange returned HTTP ${response.status}`); }
  const token = (await response.json()).access_token;
  stage = 'enrolling-device-key';
  const enrollment = await post('/backend-api/codex/remote/control/client/enroll/start', {});
  validateEnrollmentAccount(enrollment, authIdentity);
  validateStepUp(token, authIdentity.accountUserId);
  key = { ...await keyOperation({ operation: 'create' }), accountUserId: enrollment.account_user_id, clientId: enrollment.client_id };
  await writeFile(new URL('./test-key-reference.json', import.meta.url), JSON.stringify({ ...key, purpose: 'official-remote-compatibility-test' }));
  const payload = signedBytes(enrollmentPayload(enrollment.device_key_challenge, key, '/backend-api/codex/remote/control/client/enroll/finish'));
  const signature = await keyOperation({ operation: 'sign', keyId: key.keyId, payloadBase64: payload.toString('base64') });
  const enrolledSession = await post('/backend-api/codex/remote/control/client/enroll/finish', {
    client_id: key.clientId, step_up_token: token,
    device_identity: { key_id: key.keyId, public_key_spki_der_base64: key.publicKeySpkiDerBase64, algorithm: key.algorithm, protection_class: key.protectionClass },
    device_key_proof: { challenge_token: enrollment.device_key_challenge.challenge_token, key_id: key.keyId,
      signature_der_base64: signature.signatureDerBase64, signed_payload_base64: payload.toString('base64'), algorithm: signature.algorithm },
  });
  if (enrolledSession.client_id !== key.clientId || enrolledSession.account_user_id !== key.accountUserId || enrolledSession.scopes?.length !== 1 || enrolledSession.scopes[0] !== WS_SCOPE || !Number.isFinite(Date.parse(enrolledSession.expires_at)) || Date.parse(enrolledSession.expires_at) <= Date.now()) throw new Error('Enrollment session metadata mismatch');
  session = enrolledSession;
  stage = 'device-enrolled';
  await record({ stage, algorithm: key.algorithm, protectionClass: key.protectionClass, sessionVerified: true });
  const { testRemoteRead } = await import('./remote-read.mjs');
  await testRemoteRead({ authHeaders, key, session, record });
  stage = 'completed'; await record({ stage });
}
async function handle(req, res) {
  try {
    if (req.method !== 'GET' || !['localhost:1455', 'localhost:1457'].includes(req.headers.host)) { respond(res, 403, 'Forbidden'); return; }
    const url = new URL(req.url, `http://localhost:${port}`);
    if (url.pathname === '/auth/callback') {
      if (url.searchParams.get('state') !== state || callbackClaimed || !authorizationStarted || Date.now() >= deadline) { respond(res, 400, 'Invalid or expired authorization state'); return; }
      callbackClaimed = true;
      if (url.searchParams.has('error')) { respond(res, 400, html('<p>官方身份验证未完成。可返回聊天说明页面上的错误。</p>')); stage = 'authentication-failed'; await record({ stage, failure: 'oauth-authorization-error' }); return; }
      const code = url.searchParams.get('code');
      if (!code || code.length > 16384) { respond(res, 400, 'Missing authorization code'); return; }
      respond(res, 200, html('<p>身份验证已返回。本地客户端正在继续设备注册与中继连接测试。</p><p>请回到 Codex 查看结果。</p>'));
      finishEnrollment(code).catch(async error => { stage = 'failed'; await record({ stage, failure: error.message }); if (key && !session) await keyOperation({ operation: 'delete', keyId: key.keyId }).catch(() => {}); });
      return;
    }
    if (url.searchParams.get('session') !== pageKey) { respond(res, 403, 'Private test session required'); return; }
    if (url.pathname === '/status') { respond(res, 200, JSON.stringify({ stage, expiresAt: new Date(deadline).toISOString() }), 'application/json'); return; }
    if (url.pathname === '/login') {
      if (callbackClaimed || finished || Date.now() >= deadline) { respond(res, 409, html('<p>本次登录已完成或已过期。请回到聊天。</p>')); return; }
      const firstOpen = !authorizationStarted;
      authorizationStarted = true;
      const target = new URL('https://auth.openai.com/oauth/authorize');
      target.search = new URLSearchParams({ response_type: 'code', client_id: clientId, redirect_uri: redirectUri,
        scope: ENROLL_SCOPE, code_challenge: digest(verifier), code_challenge_method: 'S256', state,
        originator: 'codex_light_remote_probe', reauth: 'remote_control', max_age: '0', codex_cli_simplified_flow: 'true',
        allowed_workspace_id: auth.tokens.account_id, current_workspace_id: auth.tokens.account_id });
      res.writeHead(302, { Location: target.toString(), 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' }); res.end();
      if (firstOpen) await record({ stage: 'official-step-up-opened' }); return;
    }
    if (url.pathname === '/') { respond(res, 200, html(`<p>此测试将通过官方 OAuth 登录验证，为独立的 Codex Light 测试客户端申请设备授权。</p><p>密码、验证码和通行密钥操作均在 <strong>auth.openai.com</strong> 完成。测试进程只在内存中处理授权令牌。</p><p><a class="button" href="/login?session=${pageKey}">开始官方身份验证</a></p><p><small>这是电脑端协议验证，手机公网连接尚待实测。完成注册会创建一条独立的测试客户端授权。</small></p>`)); return; }
    respond(res, 404, 'Not found');
  } catch { if (!res.headersSent) respond(res, 500, 'Local test request failed'); else res.end(); }
}
for (const candidate of [1457, 1455]) {
  const listener = http.createServer(handle);
  try { await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(candidate, 'localhost', resolve); }); server = listener; port = candidate; break; }
  catch (error) { listener.close(); if (error.code !== 'EADDRINUSE') throw error; }
}
if (!server) throw new Error('Both official loopback callback ports are in use');
redirectUri = `http://localhost:${port}/auth/callback`;
const localUrl = `http://localhost:${port}/?session=${pageKey}`;
await writeFile(new URL('./local-test-entry.json', import.meta.url), JSON.stringify({ url: localUrl, statusUrl: `http://localhost:${port}/status?session=${pageKey}`, pid: process.pid, expiresAt: new Date(deadline).toISOString() }));
console.log(JSON.stringify({ stage, localEntryFile: 'local-test-entry.json', port }));
const expiry = setTimeout(() => stop('authorization-window-expired'), deadline - Date.now());
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => stop('test-process-stopped').finally(() => process.exit(0)));
