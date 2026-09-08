import readline from 'node:readline';

const supportedRules = new Map([
  ['email', () => /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi],
  ['bearer-token', () => /\bBearer\s+[A-Za-z0-9._~+/=-]{12,}\b/gi],
  ['api-key', () => /\b(?:sk|pk)_[A-Za-z0-9_-]{12,}\b/g],
]);

let replacement = '[REDACTED]';
let enabledRules = [...supportedRules.keys()];

function reply(id, result) {
  process.stdout.write(`${JSON.stringify({jsonrpc: '2.0', id, result})}\n`);
}

function fail(id, message) {
  process.stdout.write(`${JSON.stringify({
    jsonrpc: '2.0', id, error: {code: -32602, message},
  })}\n`);
}

function configure(config = {}) {
  replacement = typeof config.replacement === 'string'
    ? config.replacement : '[REDACTED]';
  const requested = Array.isArray(config.rules)
    ? config.rules : [...supportedRules.keys()];
  const unknown = requested.filter(name => !supportedRules.has(name));
  if (unknown.length > 0) {
    throw new Error(`unknown sanitizer rules: ${unknown.join(', ')}`);
  }
  enabledRules = [...new Set(requested)];
}

function sanitize(input) {
  if (input === null || typeof input !== 'object'
      || typeof input.text !== 'string') {
    throw new Error('sanitize input.text must be a string');
  }
  let text = input.text;
  const redactions = {};
  for (const name of enabledRules) {
    let count = 0;
    text = text.replace(supportedRules.get(name)(), () => {
      count += 1;
      return replacement;
    });
    if (count > 0) redactions[name] = count;
  }
  return {
    text,
    redactions,
    total: Object.values(redactions).reduce((sum, count) => sum + count, 0),
  };
}

readline.createInterface({input: process.stdin}).on('line', line => {
  const message = JSON.parse(line);
  const {id, method, params = {}} = message;
  try {
    if (method === 'fibra.handshake') reply(id, {protocol: 1});
    else if (method === 'fibra.ping') reply(id, {ok: true});
    else if (method === 'fibra.start') {
      configure(params.config);
      reply(id, {ok: true});
    } else if (method === 'fibra.stop') reply(id, {ok: true});
    else if (method === 'sanitize') reply(id, sanitize(params.input));
    else if (method !== '$/cancelRequest') fail(id, `unknown method: ${method}`);
  } catch (error) {
    fail(id, error instanceof Error ? error.message : String(error));
  }
});
