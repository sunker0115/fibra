import type { Assignment, ClientDisposable, ClientResourceProvider, ClientResourceRequest, ResourceDescriptor } from "@sstlfsj/fibra-client-api";
import { ClientSessionRuntime, decodeEnvelope, decodeFixtures, encodeEnvelope, type LifecyclePhase, type ProtocolEnvelope } from "@sstlfsj/fibra-client-runtime";
import { supportsWebModules, WEB_MODULE_CAPABILITY, WebModuleLoader, WebResourceLoader } from "@sstlfsj/fibra-client-runtime-web";

// Execution-local verification policy; enough for the bundled React probe.
const maximumResourceBytes = 1024 * 1024;

/** Verification-only HTTP data plane; URLs never occur in the protocol snapshot. */
export function httpProvider(endpoint: (request: ClientResourceRequest) => string, maximumResourceBytes: number): ClientResourceProvider {
  if (!Number.isSafeInteger(maximumResourceBytes) || maximumResourceBytes < 0) throw new Error("maximumResourceBytes must be a non-negative safe integer");
  return { load: async (request) => {
    const url = new URL(endpoint(request), location.origin);
    if (!/^https?:$/.test(url.protocol) || url.origin !== location.origin) throw new Error("resource origin is not permitted");
    // Reject redirects before following them, including redirects to another origin.
    const response = await fetch(url, { credentials: "omit", redirect: "error" });
    if (!response.ok || new URL(response.url).origin !== location.origin) throw new Error("resource response is not permitted");
    const length = response.headers.get("content-length");
    if (length !== null && /^[0-9]+$/.test(length) && BigInt(length) > BigInt(maximumResourceBytes)) {
      await response.body?.cancel();
      throw new Error("resource exceeds maximumResourceBytes");
    }
    const reader = response.body?.getReader();
    if (reader === undefined) return new Uint8Array();
    const chunks: Uint8Array[] = [];
    let byteLength = 0;
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        if (value.byteLength > maximumResourceBytes - byteLength) {
          await reader.cancel();
          throw new Error("resource exceeds maximumResourceBytes");
        }
        chunks.push(value); byteLength += value.byteLength;
      }
    } finally {
      reader.releaseLock();
    }
    const bytes = new Uint8Array(byteLength);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return bytes;
  } };
}

const descriptors: Record<"dom" | "react", ResourceDescriptor> = await fetch("/descriptors.json").then((response) => response.json());
const fixtures = decodeFixtures(await fetch("/fixtures.json").then((response) => response.text()));
const capabilities = await supportsWebModules() && !location.search.includes("no-capability") ? [WEB_MODULE_CAPABILITY] : [];
let disposal: Promise<void> = Promise.resolve();
let releaseDisposal = () => {};
let importing: Promise<void> = Promise.resolve();
let releaseImport = () => {};

const probe = {
  evaluations: [] as string[], events: [] as string[], createdURLs: [] as string[], revokedURLs: [] as string[],
  listenerCalls: 0, timerCalls: 0,
  failure: undefined as { renderer: "dom" | "react"; failure: "module" | "effect" } | undefined,
  waitDisposal: () => disposal,
  waitImport: () => importing,
  registerMount: (factory: (element: Element) => ClientDisposable): ClientDisposable => {
    const container = document.createElement("div");
    document.querySelector("#mounts")!.append(container);
    const mount = factory(container);
    return { dispose: async () => { await mount.dispose(); container.remove(); } };
  },
};
window.probe = probe;
const createObjectURL = URL.createObjectURL.bind(URL);
const revokeObjectURL = URL.revokeObjectURL.bind(URL);
URL.createObjectURL = (blob) => { const url = createObjectURL(blob); probe.createdURLs.push(url); return url; };
URL.revokeObjectURL = (url) => { probe.revokedURLs.push(url); revokeObjectURL(url); };

// The synthetic welcome is session-local; there is no desired state, Registry or version selection.
const session = { hostInstanceId: "verification-host", clientExecutionId: crypto.randomUUID() };
const hello = decodeEnvelope(JSON.stringify({ protocolVersion: 1, messageId: "hello", type: "client.hello", payload: {
  identity: { clientNonce: crypto.randomUUID() }, executionTarget: "client:web", capabilities,
} }));
const welcome = decodeEnvelope(JSON.stringify({ protocolVersion: 1, messageId: "welcome", type: "host.welcome", payload: { session } }));
let reads = 0;
const transport = httpProvider((request) => `/resources/${request.descriptor.digest}.js`, maximumResourceBytes);
const loader = new WebModuleLoader(capabilities);
const runtime = new ClientSessionRuntime({
  session,
  verifiedResourceLoader: new WebResourceLoader({ load: (request) => { reads += 1; return transport.load(request); } }, maximumResourceBytes),
  createModule: (assignment, context) => loader.load(assignment, context),
});

async function receive(value: ProtocolEnvelope): Promise<ProtocolEnvelope | undefined> {
  const message = decodeEnvelope(encodeEnvelope(value));
  if (message.type === "host.snapshot") { await runtime.applySnapshot(message.payload); return; }
  if (message.type === "client.detach") { await runtime.detach(message.payload.session); return; }
  if (message.type === "host.prepare" || message.type === "host.activate" || message.type === "host.drain" || message.type === "host.stop") {
    const lifecycle = message.payload.lifecycle;
    let outcome: { kind: "APPLIED" } | { kind: "FAILED"; failure: { code: string; message: string; diagnostics: Record<string, string> } };
    try {
      await runtime.execute(message.type.slice(5) as LifecyclePhase, lifecycle);
      outcome = { kind: "APPLIED" };
    } catch (failure) {
      outcome = { kind: "FAILED", failure: { code: failure instanceof Error && "code" in failure ? String(failure.code) : "LOAD_FAILED", message: String(failure), diagnostics: {} } };
    }
    return decodeEnvelope(encodeEnvelope({ protocolVersion: 1, messageId: `result-${message.messageId}`, type: "client.lifecycle-result", payload: { lifecycle, outcome } }));
  }
  throw new Error("unsupported verification command");
}

const gate = {
  fixtures, hello, welcome, runtime, receive,
  get reads() { return reads; },
  pending: Promise.resolve() as Promise<ProtocolEnvelope | undefined>,
  replacement: Promise.resolve() as Promise<ProtocolEnvelope | undefined>,
  blockDisposal: () => { disposal = new Promise<void>((resolve) => { releaseDisposal = resolve; }); },
  releaseDisposal: () => releaseDisposal(),
  blockImport: () => { importing = new Promise<void>((resolve) => { releaseImport = resolve; }); },
  releaseImport: () => releaseImport(),
  providerProbe: (endpoint: string) => httpProvider(() => endpoint, maximumResourceBytes).load({ session, targetRevision: "1", runtimeInstanceId: "probe", descriptor: descriptors.dom }),
  snapshot: (revision: string, items: readonly (readonly ["dom" | "react", string])[]): ProtocolEnvelope => ({
    protocolVersion: 1, messageId: `snapshot-${revision}`, type: "host.snapshot", payload: {
      session, viewRevision: revision, targetRevision: revision, targetDigest: descriptors.dom.digest,
      assignments: items.map(([name, runtimeInstanceId]): Assignment => ({
        pluginId: name, facetId: "web", runtimeInstanceId, executionTarget: "client:web", entryModule: "index.js",
        payloadDigest: descriptors[name].digest, requiredCapabilities: [WEB_MODULE_CAPABILITY], resources: [descriptors[name]],
      })), contributions: [],
    },
  }),
  command: (phase: LifecyclePhase, runtimeInstanceId: string, targetRevision: string): ProtocolEnvelope => ({
    protocolVersion: 1, messageId: `${phase}-${runtimeInstanceId}-${targetRevision}`, type: `host.${phase}`,
    payload: { lifecycle: { session, targetRevision, runtimeInstanceId, lifecycleOperationId: `${phase}-${runtimeInstanceId}-${targetRevision}` } },
  }),
};
window.gate = gate;

declare global {
  interface Window { gate: typeof gate; probe: typeof probe; }
}
