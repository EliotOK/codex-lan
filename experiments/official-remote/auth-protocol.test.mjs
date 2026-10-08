import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { enrollmentPayload, connectionPayload, identityHash, digest, signedBytes, validateStepUp, backendIdentity, validateEnrollmentAccount } from './auth-protocol.mjs';
const now = Date.now();
const key = { accountUserId: 'test-user', clientId: 'test-client', algorithm: 'ecdsa_p256_sha256', keyId: 'test-key', protectionClass: 'os_protected_nonextractable', publicKeySpkiDerBase64: 'test-public-key' };
const challenge = { type: 'device_key_challenge', nonce: randomBytes(32).toString('base64url'), purpose: 'remote_control_client_enrollment', audience: 'remote_control_client_enrollment', account_user_id: key.accountUserId, client_id: key.clientId, target_origin: 'https://chatgpt.com', target_path: '/backend-api/codex/remote/control/client/enroll/finish', challenge_id: 'test-challenge', challenge_expires_at: new Date(now + 60000).toISOString() };

test('legacy enrollment identity is accepted only with the bound workspace', () => {
  const token = `header.${Buffer.from(JSON.stringify({ 'https://api.openai.com/auth': {
    chatgpt_account_user_id: 'account-user', chatgpt_account_id: 'workspace', user_id: 'auth-user',
  } })).toString('base64url')}.signature`;
  const identity = backendIdentity(token, 'workspace');
  validateEnrollmentAccount({ account_user_id: 'account-user' }, identity);
  validateEnrollmentAccount({ account_user_id: 'auth-user' }, identity);
  assert.throws(() => validateEnrollmentAccount({ account_user_id: 'auth-user' }, backendIdentity(token, 'other-workspace')));
  assert.throws(() => validateEnrollmentAccount({ account_user_id: 'other-user' }, identity));
  assert.throws(() => validateEnrollmentAccount({}, identity));
  assert.throws(() => validateEnrollmentAccount({ account_user_id: 'auth-user' }, { ...identity, tokenAccountId: undefined }));
});
test('enrollment binds the actual public identity and canonical payload', () => {
  const payload = enrollmentPayload(challenge, key, challenge.target_path, { now });
  assert.equal(payload.deviceIdentitySha256Base64url, identityHash(key));
  assert.equal(JSON.parse(signedBytes(payload)).domain, 'codex-device-key-sign-payload/v1');
  for (const mutation of [{ target_origin: 'https://example.com' }, { client_id: 'different-client' }, { account_user_id: 'different-account' }, { nonce: 'short' }, { device_identity_hash: digest('different-key') }, { challenge_expires_at: new Date(now - 1).toISOString() }]) {
    assert.throws(() => enrollmentPayload({ ...challenge, ...mutation }, key, challenge.target_path, { now }));
  }
  assert.throws(() => enrollmentPayload(challenge, key, challenge.target_path, { now, refresh: true }));
});
test('websocket challenge binds client, origin, session token, expiry, and scope', () => {
  const session = { remote_control_token: 'test-session-token', expires_at: new Date(now + 60000).toISOString() };
  const c = { type: 'device_key_challenge', nonce: challenge.nonce, purpose: 'remote_control_client_websocket', audience: 'remote_control_client_websocket', targetOrigin: 'https://chatgpt.com', targetPath: '/backend-api/codex/remote/control/client', clientId: key.clientId, accountUserId: key.accountUserId, tokenSha256Base64url: digest(session.remote_control_token), tokenExpiresAt: Math.floor(Date.parse(session.expires_at) / 1000), scopes: ['remote_control_controller_websocket'], sessionId: 'test-session' };
  assert.equal(connectionPayload(c, key, session, { now }).sessionId, c.sessionId);
  for (const mutation of [{ tokenSha256Base64url: digest('other-token') }, { tokenExpiresAt: c.tokenExpiresAt + 1 }, { scopes: ['other-scope'] }, { targetPath: '/other-path' }, { clientId: 'other-client' }]) assert.throws(() => connectionPayload({ ...c, ...mutation }, key, session, { now }));
});

test('live Unix-second challenge expiry stays unchanged in the signed proof', () => {
  const expires = Math.floor(now / 1000) + 60;
  const payload = enrollmentPayload({ ...challenge, challenge_expires_at: expires }, key, challenge.target_path, { now });
  assert.equal(payload.challengeExpiresAt, expires);
  for (const invalid of [Math.floor(now / 1000) - 1, null, {}, NaN, Infinity, 'invalid-date']) {
    assert.throws(() => enrollmentPayload({ ...challenge, challenge_expires_at: invalid }, key, challenge.target_path, { now }));
  }
});
test('step-up accepts only the current account and fresh enrollment authorization', () => {
  const claims = { iat: Math.floor(now / 1000), pwd_auth_time: now, scope: 'codex.remote_control.enroll', 'https://api.openai.com/auth': { chatgpt_account_user_id: key.accountUserId } };
  const token = value => `header.${Buffer.from(JSON.stringify(value)).toString('base64url')}.signature`;
  validateStepUp(token(claims), key.accountUserId, now);
  assert.throws(() => validateStepUp(token(claims), 'different-account', now));
  assert.throws(() => validateStepUp(token({ ...claims, scope: 'other-scope' }), key.accountUserId, now));
  assert.throws(() => validateStepUp(token({ ...claims, pwd_auth_time: now - 301000 }), key.accountUserId, now));
});
