import { spawn, spawnSync } from 'node:child_process';

const privateArgs = process.argv.slice(1);
const linuxScope = privateArgs[0] === 'linux-scope';
const [graceArg, quiescenceArg, outArg, errArg, executable, ...argv] =
  linuxScope ? privateArgs.slice(1) : privateArgs;
const grace = Number(graceArg);
const quiescence = Number(quiescenceArg);
const encode = value => Buffer.from(String(value)).toString('base64');
const record = (...values) => process.stdout.write(values.join('\t') + '\n');
if (linuxScope) {
  try {
    const frame = await invocationFrame();
    if (frame.present) process.env.INVOCATION_ID = frame.value;
    else delete process.env.INVOCATION_ID;
  } catch (error) {
    record('E', 'SPAWN_FAILED', encode(error.message));
    record('Q');
    await new Promise(resolve => process.stdout.end(resolve));
    process.exit(0);
  }
}
const collected = limit => ({ limit: Number(limit), bytes: 0, kept: 0, chunks: [] });
const stdout = collected(outArg);
const stderr = collected(errArg);
const payload = spawn(executable, argv, {
  detached: process.platform !== 'win32', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
});

async function invocationFrame() {
  const maximum = 64;
  const line = await new Promise((resolve, reject) => {
    let buffered = Buffer.alloc(0);
    const cleanup = () => {
      process.stdin.off('data', onData);
      process.stdin.off('end', onEnd);
      process.stdin.off('error', onError);
    };
    const fail = error => { cleanup(); reject(error); };
    const onEnd = () => fail(new Error('missing Linux scope invocation frame'));
    const onError = error => fail(error);
    const onData = chunk => {
      buffered = Buffer.concat([buffered, chunk]);
      if (buffered.length > maximum) return fail(new Error('invalid Linux scope invocation frame'));
      const newline = buffered.indexOf(0x0a);
      if (newline < 0) return;
      process.stdin.pause();
      cleanup();
      const remainder = buffered.subarray(newline + 1);
      if (remainder.length > 0) process.stdin.unshift(remainder);
      resolve(buffered.subarray(0, newline).toString('utf8'));
    };
    process.stdin.on('data', onData);
    process.stdin.once('end', onEnd);
    process.stdin.once('error', onError);
    process.stdin.resume();
  });
  const fields = line.split('\t');
  if (fields.length !== 3 || fields[0] !== 'I' || !['0', '1'].includes(fields[1])) {
    throw new Error('invalid Linux scope invocation frame');
  }
  if (fields[1] === '0') {
    if (fields[2] !== '') throw new Error('invalid Linux scope invocation frame');
    return { present: false, value: '' };
  }
  const decoded = Buffer.from(fields[2], 'base64');
  if (decoded.toString('base64') !== fields[2]) throw new Error('invalid Linux scope invocation frame');
  const value = decoded.toString('utf8');
  if (!/^[0-9a-fA-F]{32}$/u.test(value)) throw new Error('invalid Linux scope invocation frame');
  return { present: true, value };
}
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
process.stdin.once('end', beginShutdown);
process.stdin.once('error', beginShutdown);
if (process.stdin.readableEnded) beginShutdown();
else process.stdin.resume();
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
