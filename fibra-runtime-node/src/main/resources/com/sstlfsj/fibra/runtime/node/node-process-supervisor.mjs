import { spawn, spawnSync } from 'node:child_process';

const [, , entrypoint, rawTerminateTimeout] = process.argv;
const terminateTimeout = Number.parseInt(rawTerminateTimeout, 10);

if (!entrypoint || !Number.isSafeInteger(terminateTimeout) || terminateTimeout <= 0) {
  process.stderr.write('Fibra Node supervisor received invalid launch arguments\n');
  process.exit(64);
}

const payload = spawn(process.execPath, [entrypoint], {
  cwd: process.cwd(),
  detached: process.platform !== 'win32',
  stdio: ['pipe', 'pipe', 'pipe'],
  windowsHide: true,
});

payload.stdout.pipe(process.stdout, { end: false });
payload.stderr.pipe(process.stderr, { end: false });
process.stdin.pipe(payload.stdin);

let shutdown;
let payloadOutcome;

payload.once('error', error => {
  process.stderr.write(`Fibra Node payload failed to start: ${error.message}\n`);
  beginShutdown(1);
});

payload.once('exit', (code, signal) => {
  payloadOutcome = code ?? signalExitCode(signal);
  beginShutdown(payloadOutcome);
});

process.stdin.once('end', () => beginShutdown(0));
process.stdin.once('error', error => {
  process.stderr.write(`Fibra Node supervisor input failed: ${error.message}\n`);
  beginShutdown(1);
});

for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) {
  process.once(signal, () => beginShutdown(128 + signalNumber(signal)));
}

function beginShutdown(exitCode) {
  if (shutdown) return shutdown;
  shutdown = terminateManagedRange()
    .then(quiescent => {
      if (!quiescent) {
        process.stderr.write('Fibra Node managed process range did not become quiescent\n');
        process.exitCode = 1;
      } else {
        process.exitCode = payloadOutcome ?? exitCode;
      }
      process.stdin.pause();
      process.stdout.end();
      process.stderr.end();
    })
    .catch(error => {
      process.stderr.write(`Fibra Node managed process range termination failed: ${error.message}\n`);
      process.exitCode = 1;
      process.stdin.pause();
      process.stdout.end();
      process.stderr.end();
    });
  return shutdown;
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

  signalProcessGroup('SIGTERM');
  const termDeadline = startedAt + Math.max(2, Math.floor(terminateTimeout * 2 / 3));
  if (await waitUntilQuiescent(termDeadline)) return true;

  signalProcessGroup('SIGKILL');
  return waitUntilQuiescent(startedAt + terminateTimeout);
}

async function waitUntilQuiescent(deadline) {
  while (managedRangeExists()) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) return false;
    await delay(Math.min(20, remaining));
  }
  return true;
}

function managedRangeExists() {
  if (!Number.isInteger(payload.pid)) return false;
  if (process.platform === 'win32') return payload.exitCode === null;
  try {
    process.kill(-payload.pid, 0);
    return true;
  } catch (error) {
    if (error.code === 'ESRCH') return false;
    throw error;
  }
}

function signalProcessGroup(signal) {
  if (!Number.isInteger(payload.pid)) return;
  try {
    process.kill(-payload.pid, signal);
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

function signalNumber(signal) {
  return { SIGHUP: 1, SIGINT: 2, SIGKILL: 9, SIGTERM: 15 }[signal] ?? 0;
}

function signalExitCode(signal) {
  return signal ? 128 + signalNumber(signal) : 1;
}
