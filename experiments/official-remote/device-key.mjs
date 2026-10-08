import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';
const candidates = [process.env.REMOTE_PROBE_PWSH,
  path.join(process.env.ProgramFiles ?? 'C:/Program Files', 'PowerShell', '7', 'pwsh.exe'),
  path.join(homedir(), '.cache', 'codex-runtimes', 'codex-primary-runtime', 'dependencies', 'native', 'powershell', 'pwsh.exe')];
const executable = candidates.find(candidate => candidate && existsSync(candidate)) ?? 'pwsh.exe';
const script = fileURLToPath(new URL('./device-key.ps1', import.meta.url));
export async function keyOperation(request) {
  return await new Promise((resolve, reject) => {
    const child = spawn(executable, ['-NoProfile', '-File', script], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let text = '', done = false;
    const finish = (error, result) => { if (done) return; done = true; clearTimeout(timer); error ? reject(error) : resolve(result); };
    const timer = setTimeout(() => { child.kill(); finish(new Error('Device-key helper timed out')); }, 15000);
    child.stdout.on('data', bytes => { text += bytes; if (text.length > 65536) { child.kill(); finish(new Error('Device-key helper response too large')); } });
    child.stderr.on('data', () => {});
    child.on('error', () => finish(new Error('Device-key helper could not start')));
    child.on('close', code => { try { const result = JSON.parse(text); finish(code || result.error ? new Error('Device-key operation failed') : null, result); } catch { finish(new Error('Invalid device-key response')); } });
    child.stdin.on('error', () => finish(new Error('Device-key helper input failed')));
    child.stdin.end(JSON.stringify(request) + '\n');
  });
}
