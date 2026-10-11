import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';

const [, , entrypoint, rawTerminateTimeout, terminationStatus] = process.argv;
const terminateTimeout = Number.parseInt(rawTerminateTimeout, 10);

if (!entrypoint || !terminationStatus || !Number.isSafeInteger(terminateTimeout) || terminateTimeout <= 0) {
  process.stderr.write('Fibra Node supervisor received invalid launch arguments\n');
  process.exit(64);
}

const payload = spawn(process.execPath, [entrypoint], {
  cwd: process.cwd(),
  detached: process.platform !== 'win32',
  stdio: ['pipe', 'pipe', 'pipe'],
  windowsHide: true,
});

const output = new net.Socket({ fd: 1, readable: false, writable: true });
output.once('close', () => {
  try {
    // libuv 保留 fd 0..2；等 socket handle 关闭后再释放独占的 fd1。
    fs.closeSync(1);
  } catch (error) {
    if (error.code === 'EBADF') return;
    reportFailure(`Fibra Node supervisor output close failed: ${error.message}\n`);
    process.exitCode = 1;
    beginShutdown();
  }
});
payload.stdout.pipe(output, { end: false });
payload.stdout.once('end', () => output.destroySoon());
payload.stderr.pipe(process.stderr, { end: false });
process.stdin.pipe(payload.stdin);

let shutdown;
let payloadOutcome;
let resolvePayloadOutcome;
let stderrFailed = false;
let rangeAbsent = false;
let firstBlockedRangeOperation;
let lastBlockedRangeOperation;
const payloadCompletion = new Promise(resolve => { resolvePayloadOutcome = resolve; });

process.stderr.on('error', () => {
  stderrFailed = true;
  process.exitCode = 1;
  payload.stderr.unpipe(process.stderr);
  payload.stderr.resume();
  beginShutdown();
});

output.once('error', error => {
  reportFailure(`Fibra Node supervisor output failed: ${error.message}\n`);
  process.exitCode = 1;
  payload.stdout.resume();
  beginShutdown();
});

payload.once('error', error => {
  payloadOutcome = { kind: 'START_FAILED', error: error.message };
  resolvePayloadOutcome();
  reportFailure(`Fibra Node payload failed to start: ${error.message}\n`);
  beginShutdown();
});

payload.once('exit', (code, signal) => {
  payloadOutcome = signal
    ? { kind: 'SIGNALLED', signal }
    : { kind: 'EXITED', exitCode: code };
  resolvePayloadOutcome();
  beginShutdown();
});

process.stdin.once('end', () => beginShutdown());
process.stdin.once('error', error => {
  reportFailure(`Fibra Node supervisor input failed: ${error.message}\n`);
  process.exitCode = 1;
  beginShutdown();
});

payload.stdin.once('error', error => {
  process.stdin.unpipe(payload.stdin);
  process.stdin.pause();
  if (!shutdown || error.code !== 'EPIPE') {
    reportFailure(`Fibra Node payload input failed: ${error.message}\n`);
    process.exitCode = 1;
  }
  beginShutdown();
});

for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) {
  process.once(signal, () => beginShutdown());
}

function beginShutdown() {
  if (shutdown) return shutdown;
  shutdown = Promise.resolve()
    .then(terminateManagedRange)
    .then(async quiescent => {
      if (!quiescent) {
        writeTerminationStatus('FAILED');
        reportFailure(`Fibra Node managed process range did not become quiescent${blockedRangeDiagnostic()}\n`);
        process.exitCode = 1;
      } else {
        await payloadCompletion;
        process.exitCode = writeTerminationStatus('QUIESCENT') ? process.exitCode ?? 0 : 1;
      }
      process.stdin.pause();
    })
    .catch(error => {
      process.exitCode = 1;
      reportFailure(`Fibra Node managed process range termination failed: ${error.message}\n`);
      writeTerminationStatus('FAILED');
      process.stdin.pause();
    });
  return shutdown;
}

function writeTerminationStatus(status) {
  try {
    fs.writeFileSync(terminationStatus,
      `${JSON.stringify({ payload: payloadOutcome ?? null, range: status })}\n`,
      { encoding: 'utf8', flag: 'wx' });
    return true;
  } catch (error) {
    reportFailure(`Fibra Node termination status write failed: ${error.message}\n`);
    return false;
  }
}

function reportFailure(message) {
  if (!stderrFailed) process.stderr.write(message);
}

async function terminateManagedRange() {
  payload.stdin.end();
  const startedAt = Date.now();
  const eofDeadline = startedAt + Math.max(1, Math.floor(terminateTimeout / 3));
  if (await waitUntilQuiescent(eofDeadline)) return true;

  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/PID', String(payload.pid), '/T', '/F'], {
      stdio: 'ignore',
      timeout: Math.max(1, startedAt + terminateTimeout - Date.now()),
      windowsHide: true,
    });
    return waitUntilQuiescent(startedAt + terminateTimeout);
  }

  if (signalProcessGroup('SIGTERM') === 'ABSENT') return true;
  const termDeadline = startedAt + Math.max(2, Math.floor(terminateTimeout * 2 / 3));
  if (await waitUntilQuiescent(termDeadline)) return true;

  if (signalProcessGroup('SIGKILL') === 'ABSENT') return true;
  return waitUntilQuiescent(startedAt + terminateTimeout);
}

async function waitUntilQuiescent(deadline) {
  while (managedRangeState() !== 'ABSENT') {
    const remaining = deadline - Date.now();
    if (remaining <= 0) return false;
    await delay(Math.min(20, remaining));
  }
  return true;
}

function managedRangeState() {
  if (rangeAbsent) return 'ABSENT';
  if (!Number.isInteger(payload.pid)) return 'ABSENT';
  if (process.platform === 'win32') return payload.exitCode === null ? 'PRESENT' : 'ABSENT';
  try {
    process.kill(-payload.pid, 0);
    return 'PRESENT';
  } catch (error) {
    return rangeOperationFailure(0, error);
  }
}

function signalProcessGroup(signal) {
  if (rangeAbsent || !Number.isInteger(payload.pid)) return 'ABSENT';
  try {
    process.kill(-payload.pid, signal);
    return 'PRESENT';
  } catch (error) {
    return rangeOperationFailure(signal, error);
  }
}

function rangeOperationFailure(operation, error) {
  if (error.code === 'ESRCH') {
    // 本次 owner 的消失证明不可逆，随后不再使用可能复用的 PGID。
    rangeAbsent = true;
    return 'ABSENT';
  }
  if (error.code === 'EPERM') {
    const blocked = `operation=${operation} errno=EPERM pgid=${payload.pid}`;
    firstBlockedRangeOperation ??= blocked;
    lastBlockedRangeOperation = blocked;
    return 'UNKNOWN';
  }
  throw error;
}

function blockedRangeDiagnostic() {
  return firstBlockedRangeOperation === undefined ? ''
    : ` (first blocked ${firstBlockedRangeOperation}; last blocked ${lastBlockedRangeOperation})`;
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}
