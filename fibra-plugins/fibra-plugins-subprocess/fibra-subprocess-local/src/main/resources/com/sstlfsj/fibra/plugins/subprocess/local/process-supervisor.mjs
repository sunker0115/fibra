import { spawn, spawnSync } from 'node:child_process';

const [graceArg, quiescenceArg, outArg, errArg, executable, ...argv] = process.argv.slice(1);
const grace = Number(graceArg);
const quiescence = Number(quiescenceArg);
const encode = value => Buffer.from(String(value)).toString('base64');
const record = (...values) => process.stdout.write(values.join('\t') + '\n');
const collected = limit => ({ limit: Number(limit), bytes: 0, kept: 0, chunks: [] });
const stdout = collected(outArg);
const stderr = collected(errArg);
const payload = spawn(executable, argv, {
  detached: process.platform !== 'win32', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
});
let shutdown;
let outcomePublication;
let outputFailure;
const collect = (stream, target) => {
  stream.on('data', chunk => {
    target.bytes += chunk.length;
    target.chunks.push(Buffer.from(chunk));
    target.kept += chunk.length;
    while (target.kept > target.limit) {
      const excess = target.kept - target.limit;
      const head = target.chunks[0];
      if (head.length <= excess) {
        target.chunks.shift();
        target.kept -= head.length;
      } else {
        target.chunks[0] = head.subarray(excess);
        target.kept -= excess;
      }
    }
  });
  stream.on('error', error => {
    outputFailure = error;
    record('E', 'OUTPUT_FAILED', encode(error.message));
    beginShutdown();
  });
};
collect(payload.stdout, stdout);
collect(payload.stderr, stderr);
const streamClosed = stream => new Promise(resolve => {
  const settle = () => {
    stream.off('end', settle);
    stream.off('close', settle);
    stream.off('error', settle);
    resolve();
  };
  stream.once('end', settle);
  stream.once('close', settle);
  stream.once('error', settle);
});
const outputClosed = Promise.all([streamClosed(payload.stdout), streamClosed(payload.stderr)]);
payload.once('spawn', () => record('P', payload.pid));
payload.once('error', error => {
  record('E', 'SPAWN_FAILED', encode(error.message));
  beginShutdown();
});
payload.once('exit', (code, signal) => {
  outcomePublication = publishOutcome(code, signal);
  beginShutdown();
});
process.stdin.resume();
process.stdin.once('end', beginShutdown);
process.stdin.once('error', beginShutdown);
for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.once(signal, beginShutdown);

function exists() {
  if (!Number.isInteger(payload.pid)) return false;
  if (process.platform === 'win32') return payload.exitCode === null && payload.signalCode === null;
  try { process.kill(-payload.pid, 0); return true; }
  catch (error) {
    if (error.code === 'ESRCH') return false;
    if (error.code === 'EPERM') return true;
    throw error;
  }
}
function signalGroup(signal) {
  try { process.kill(-payload.pid, signal); }
  catch (error) {
    if (error.code === 'ESRCH') return;
    try { payload.kill(signal); } catch { /* The direct child already exited. */ }
  }
}
async function wait(deadline) {
  while (exists()) {
    if (Date.now() >= deadline) return false;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  return true;
}
async function publishOutcome(code, signal) {
  let timer;
  await Promise.race([
    outputClosed,
    new Promise(resolve => { timer = setTimeout(resolve, grace); }),
  ]);
  if (timer !== undefined) clearTimeout(timer);
  if (outputFailure) return;
  const output = target => [Buffer.concat(target.chunks).toString('base64'),
    target.bytes > target.limit, target.bytes];
  record('D', code ?? '', signal ?? '', ...output(stdout), ...output(stderr));
}
function beginShutdown() {
  shutdown ??= cleanup().catch(error => {
    record('E', 'TERMINATION_FAILED', encode(error.message));
    process.stdout.end(() => process.exit(1));
  });
  return shutdown;
}
async function cleanup() {
  if (exists()) {
    if (process.platform === 'win32') {
      const killed = spawnSync('taskkill', ['/PID', String(payload.pid), '/T', '/F'],
        { stdio: 'ignore', timeout: grace, windowsHide: true });
      if (killed.error || killed.status !== 0) throw new Error('taskkill tree termination failed');
    } else {
      signalGroup('SIGTERM');
      if (!await wait(Date.now() + grace)) signalGroup('SIGKILL');
    }
    if (!await wait(Date.now() + quiescence)) {
      throw new Error('managed process range did not become quiescent');
    }
  }
  if (Number.isInteger(payload.pid) && outcomePublication === undefined) {
    await new Promise(resolve => payload.once('exit', resolve));
  }
  if (outcomePublication !== undefined) await outcomePublication;
  record('Q');
  process.stdout.end(() => process.exit(0));
}
