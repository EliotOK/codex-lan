import https from 'node:https';
import { createHash, randomBytes } from 'node:crypto';
const MAX = 16 * 1024 * 1024;
export function clientFrame(bytes, opcode = 1) {
  const payload = Buffer.from(bytes);
  if (payload.length > MAX) throw new Error('Websocket payload exceeds probe limit');
  const size = payload.length < 126 ? 2 : payload.length <= 65535 ? 4 : 10;
  const header = Buffer.alloc(size + 4);
  header[0] = 128 | opcode;
  if (size === 2) header[1] = 128 | payload.length;
  else if (size === 4) { header[1] = 128 | 126; header.writeUInt16BE(payload.length, 2); }
  else { header[1] = 128 | 127; header.writeBigUInt64BE(BigInt(payload.length), 2); }
  const mask = randomBytes(4); mask.copy(header, size);
  for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
  return Buffer.concat([header, payload]);
}
export function serverFrame(buffer) {
  if (buffer.length < 2) return;
  if (buffer[0] & 112 || buffer[1] & 128) throw new Error('Invalid websocket server flags');
  let size = buffer[1] & 127, offset = 2;
  if (size === 126) { if (buffer.length < 4) return; size = buffer.readUInt16BE(2); offset = 4; }
  else if (size === 127) { if (buffer.length < 10) return; const value = buffer.readBigUInt64BE(2); if (value > BigInt(MAX)) throw new Error('Websocket frame too large'); size = Number(value); offset = 10; }
  if (size > MAX) throw new Error('Websocket frame too large');
  const opcode = buffer[0] & 15, fin = !!(buffer[0] & 128);
  if (opcode >= 8 && (!fin || size > 125)) throw new Error('Invalid websocket control frame');
  if (buffer.length < offset + size) return;
  return { opcode, fin, payload: buffer.subarray(offset, offset + size), remaining: buffer.subarray(offset + size) };
}
export class WireWebSocket {
  constructor(socket) {
    this.socket = socket; this.buffer = Buffer.alloc(0); this.fragments = []; this.fragmentSize = 0; this.fragmenting = false;
    this.queue = []; this.waiters = []; this.failure = null;
    socket.on('data', bytes => { try { this.receive(bytes); } catch (error) { this.fail(error); socket.destroy(); } });
    socket.on('error', () => this.fail(new Error('Websocket transport error')));
    socket.on('end', () => this.fail(new Error('Websocket ended')));
    socket.on('close', () => this.fail(new Error('Websocket closed')));
  }
  static async connect(headers) {
    const key = randomBytes(16).toString('base64');
    return await new Promise((resolve, reject) => {
      const request = https.request('https://chatgpt.com/backend-api/codex/remote/control/client', {
        headers: { ...headers, Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Key': key, 'Sec-WebSocket-Version': '13' }, timeout: 15000,
      });
      request.on('response', response => { response.resume(); reject(new Error(`Websocket rejected HTTP ${response.statusCode}`)); });
      request.on('timeout', () => request.destroy(new Error('Websocket upgrade timed out')));
      request.on('error', error => { const code = /^[A-Z0-9_]+$/.test(error.code ?? '') ? error.code : 'UNKNOWN'; reject(new Error(`Websocket upgrade transport failed (${code})`)); });
      request.on('upgrade', (response, socket, head) => {
        const accept = createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
        if (response.statusCode !== 101 || response.headers['sec-websocket-accept'] !== accept || response.headers.upgrade?.toLowerCase() !== 'websocket') { socket.destroy(); reject(new Error('Invalid websocket upgrade response')); return; }
        const connection = new WireWebSocket(socket);
        try { if (head.length) connection.receive(head); resolve(connection); } catch (error) { socket.destroy(); reject(error); }
      });
      request.end();
    });
  }
  fail(error) {
    if (this.failure) return; this.failure = error;
    for (const waiter of this.waiters.splice(0)) { clearTimeout(waiter.timer); waiter.reject(error); }
  }
  deliver(message) {
    const waiter = this.waiters.shift();
    if (waiter) { clearTimeout(waiter.timer); waiter.resolve(message); }
    else { if (this.queue.length >= 256) throw new Error('Websocket message queue full'); this.queue.push(message); }
  }
  receive(bytes) {
    this.buffer = Buffer.concat([this.buffer, bytes]);
    if (this.buffer.length > MAX + 10) throw new Error('Websocket input buffer too large');
    for (;;) {
      const frame = serverFrame(this.buffer); if (!frame) break; this.buffer = frame.remaining;
      if (frame.opcode === 8) { this.socket.destroy(); this.fail(new Error('Server closed websocket')); return; }
      if (frame.opcode === 9) { this.socket.write(clientFrame(frame.payload, 10)); continue; }
      if (frame.opcode === 10) continue;
      if (frame.opcode !== 0 && frame.opcode !== 1 || frame.opcode === 0 && !this.fragmenting || frame.opcode === 1 && this.fragmenting) throw new Error('Unsupported websocket message');
      this.fragments.push(frame.payload); this.fragmentSize += frame.payload.length;
      if (this.fragmentSize > MAX) throw new Error('Websocket message too large');
      if (!frame.fin) { this.fragmenting = true; continue; }
      const message = JSON.parse(Buffer.concat(this.fragments, this.fragmentSize).toString('utf8'));
      this.fragments = []; this.fragmentSize = 0; this.fragmenting = false; this.deliver(message);
    }
  }
  async next(timeout = 15000) {
    if (this.queue.length) return this.queue.shift();
    if (this.failure) throw this.failure;
    return await new Promise((resolve, reject) => {
      const waiter = { resolve, reject };
      waiter.timer = setTimeout(() => { this.waiters = this.waiters.filter(x => x !== waiter); reject(new Error('Websocket message timed out')); }, timeout);
      this.waiters.push(waiter);
    });
  }
  send(message) { if (this.failure || this.socket.destroyed) throw new Error('Websocket unavailable'); this.socket.write(clientFrame(JSON.stringify(message))); }
  close() { this.socket.destroy(); this.fail(new Error('Probe websocket closed')); }
}
