import test from 'node:test';
import assert from 'node:assert/strict';
import { clientFrame, serverFrame } from './wire-websocket.mjs';
test('client framing masks bytes and handles short and extended lengths', () => {
  for (const size of [2, 126, 65536]) {
    const bytes = Buffer.alloc(size, 65), frame = clientFrame(bytes);
    assert.equal(frame[0], 129); assert.equal(frame[1] & 128, 128);
    const offset = size < 126 ? 2 : size <= 65535 ? 4 : 10;
    const recovered = Buffer.from(frame.subarray(offset + 4));
    for (let i = 0; i < size; i++) recovered[i] ^= frame[offset + i % 4];
    assert.deepEqual(recovered, bytes);
  }
});
test('server framing waits for partial bytes and rejects malformed control and masked frames', () => {
  assert.equal(serverFrame(Buffer.from([129])), undefined);
  assert.equal(serverFrame(Buffer.from([129, 2, 65])), undefined);
  const result = serverFrame(Buffer.from([129, 2, 65, 66, 1]));
  assert.equal(result.payload.toString(), 'AB'); assert.equal(result.remaining.length, 1);
  assert.throws(() => serverFrame(Buffer.from([129, 128])));
  assert.throws(() => serverFrame(Buffer.from([9, 0])));
  assert.throws(() => serverFrame(Buffer.from([193, 0])));
});
