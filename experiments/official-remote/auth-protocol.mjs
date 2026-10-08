import { createHash } from 'node:crypto';

export const ORIGIN = 'https://chatgpt.com';
export const ENROLL_SCOPE = 'codex.remote_control.enroll';
export const WS_SCOPE = 'remote_control_controller_websocket';
export const digest = text => createHash('sha256').update(text).digest('base64url');
const must = (condition, message) => { if (!condition) throw new Error(message); };
const validNonce = value => typeof value === 'string' && /^[A-Za-z0-9_-]+$/.test(value) && Buffer.from(value, 'base64url').length >= 32;
export function backendIdentity(token, headerAccountId) {
  const claim = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
  const auth = claim['https://api.openai.com/auth'];
  const accountUserId = auth?.chatgpt_account_user_id ?? auth?.account_user_id;
  must(typeof accountUserId === 'string' && accountUserId.length > 0, 'Backend account user identity unavailable');
  return { accountUserId, tokenAccountId: auth?.chatgpt_account_id ?? auth?.account_id,
    authUserId: auth?.user_id, headerAccountId };
}
export function validateEnrollmentAccount(enrollment, identity) {
  const responseId = enrollment.account_user_id;
  must(typeof responseId === 'string' && responseId.length > 0, 'Enrollment account identity unavailable');
  const direct = responseId === identity.accountUserId;
  const legacy = typeof identity.tokenAccountId === 'string' && identity.tokenAccountId.length > 0
    && identity.headerAccountId === identity.tokenAccountId && responseId === identity.authUserId;
  must(direct || legacy, 'Enrollment does not match current account');
}
export function identityHash(key) {
  return digest(JSON.stringify({ algorithm: key.algorithm, keyId: key.keyId,
    protectionClass: key.protectionClass, publicKeySpkiDerBase64: key.publicKeySpkiDerBase64 }));
}
export function enrollmentPayload(challenge, key, path, { refresh = false, now = Date.now() } = {}) {
  must(challenge.type === 'device_key_challenge' && validNonce(challenge.nonce), 'Invalid enrollment challenge');
  must(challenge.purpose === 'remote_control_client_enrollment' && challenge.audience === 'remote_control_client_enrollment', 'Invalid enrollment purpose');
  must(challenge.target_origin === ORIGIN && challenge.target_path === path, 'Invalid enrollment target');
  must(challenge.client_id === key.clientId && challenge.account_user_id === key.accountUserId, 'Enrollment identity mismatch');
  const expiry = challenge.challenge_expires_at;
  const expiresAt = Number.isSafeInteger(expiry) ? expiry * 1000
    : typeof expiry === 'string' ? Date.parse(expiry) : NaN;
  must(Number.isFinite(expiresAt) && expiresAt > now, 'Enrollment challenge expired');
  const hash = identityHash(key);
  must(!refresh || challenge.device_identity_hash === hash, 'Missing refresh identity binding');
  must(challenge.device_identity_hash == null || challenge.device_identity_hash === hash, 'Device identity mismatch');
  return { accountUserId: key.accountUserId, audience: challenge.audience,
    challengeExpiresAt: challenge.challenge_expires_at, challengeId: challenge.challenge_id,
    clientId: key.clientId, deviceIdentitySha256Base64url: hash, nonce: challenge.nonce,
    targetOrigin: challenge.target_origin, targetPath: challenge.target_path, type: 'remoteControlClientEnrollment' };
}
export function connectionPayload(challenge, key, session, { now = Date.now() } = {}) {
  must(challenge.type === 'device_key_challenge' && validNonce(challenge.nonce), 'Invalid websocket challenge');
  must(challenge.purpose === 'remote_control_client_websocket' && challenge.audience === 'remote_control_client_websocket', 'Invalid websocket purpose');
  must(challenge.targetOrigin === ORIGIN && challenge.targetPath === '/backend-api/codex/remote/control/client', 'Invalid websocket target');
  must(challenge.clientId === key.clientId && challenge.accountUserId === key.accountUserId, 'Websocket identity mismatch');
  must(challenge.tokenSha256Base64url === digest(session.remote_control_token), 'Websocket token mismatch');
  must(challenge.tokenExpiresAt === Math.floor(Date.parse(session.expires_at) / 1000) && challenge.tokenExpiresAt > now / 1000, 'Websocket expiry mismatch');
  must(challenge.scopes?.length === 1 && challenge.scopes[0] === WS_SCOPE, 'Websocket scope mismatch');
  return { accountUserId: key.accountUserId, audience: challenge.audience, clientId: key.clientId,
    nonce: challenge.nonce, scopes: challenge.scopes, sessionId: challenge.sessionId,
    targetOrigin: challenge.targetOrigin, targetPath: challenge.targetPath,
    tokenExpiresAt: challenge.tokenExpiresAt, tokenSha256Base64url: challenge.tokenSha256Base64url,
    type: 'remoteControlClientConnection' };
}
export const signedBytes = payload => Buffer.from(JSON.stringify({ domain: 'codex-device-key-sign-payload/v1', payload }));
export function validateStepUp(token, accountUserId, now = Date.now()) {
  const claim = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
  const auth = claim['https://api.openai.com/auth'];
  must((auth?.chatgpt_account_user_id ?? auth?.account_user_id) === accountUserId, 'Step-up account mismatch');
  const scopes = [...new Set([...(claim.scope?.split(/\s+/).filter(Boolean) ?? []), ...(claim.scp ?? [])])];
  must(scopes.length === 1 && scopes[0] === ENROLL_SCOPE, 'Step-up scope mismatch');
  must(Number.isFinite(claim.iat) && Math.abs(now / 1000 - claim.iat) <= 300, 'Step-up token is not fresh');
  must(Number.isFinite(claim.pwd_auth_time) && Math.abs(now - claim.pwd_auth_time) <= 300000, 'Step-up identity verification is not fresh');
}
