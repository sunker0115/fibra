import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { describe, it } from "node:test";
import type { Assignment, ClientContext, ClientResourceRequest } from "@sstlfsj/fibra-client-api";
import { Scope, VerifiedResourceCache } from "@sstlfsj/fibra-client-runtime";
import { WEB_MODULE_CAPABILITY, WebModuleLoader, WebResourceLoader } from "../src/module-loader.js";

const bytes = new TextEncoder().encode("export const value = 1;");
const request: ClientResourceRequest = {
  session: { hostInstanceId: "host", clientExecutionId: "client" },
  targetRevision: "1", runtimeInstanceId: "instance",
  descriptor: { path: "entry.js", digest: createHash("sha256").update(bytes).digest("hex"), byteLength: String(bytes.length) },
};

describe("WebResourceLoader within the core verified cache", () => {
  it("single-flights provider reading AND verification, and reuses verified bytes", async () => {
    let reads = 0;
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const cache = new VerifiedResourceCache();
    const loader = new WebResourceLoader({ load: async (actual) => {
      assert.deepEqual(actual, request); reads += 1; await gate; return bytes;
    } }, bytes.length);
    const load = () => cache.loadVerified(request.descriptor, () => loader.loadVerified(request));
    const first = load();
    const second = load();
    release();
    const values = await Promise.all([first, second, load()]);
    assert.equal(reads, 1);
    assert.deepEqual(values[0]?.bytes, bytes);
    values[0]!.bytes.fill(0);
    assert.deepEqual(values[1]?.bytes, bytes);
    const again = await load();
    assert.deepEqual(again.bytes, bytes);
    assert.equal(createHash("sha256").update(again.bytes).digest("hex"), request.descriptor.digest);
    assert.equal(reads, 1);
  });

  for (const invalid of [new Uint8Array(), new Uint8Array(bytes.length)]) {
    it(`does not cache failed ${invalid.length ? "digest" : "size"} verification and retries provider`, async () => {
      let reads = 0;
      const cache = new VerifiedResourceCache();
      const loader = new WebResourceLoader({ load: async () => ++reads === 1 ? invalid : bytes }, bytes.length);
      const load = () => cache.loadVerified(request.descriptor, () => loader.loadVerified(request));
      await assert.rejects(Promise.all([load(), load()]), /byteLength|SHA-256/);
      assert.equal(reads, 1);
      assert.deepEqual((await load()).bytes, bytes);
      assert.equal(reads, 2);
    });
  }

  it("rejects noncanonical sizes and uppercase digests", async () => {
    const loader = new WebResourceLoader({ load: async () => bytes }, bytes.length);
    for (const descriptor of [
      { ...request.descriptor, byteLength: `0${bytes.length}` },
      { ...request.descriptor, digest: request.descriptor.digest.toUpperCase() },
    ]) await assert.rejects(loader.loadVerified({ ...request, descriptor }), /byteLength|SHA-256/);
  });

  it("requires a non-negative safe integer resource budget", () => {
    for (const limit of [-1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
      assert.throws(() => new WebResourceLoader({ load: async () => bytes }, limit), /maximumResourceBytes/);
    }
    assert.doesNotThrow(() => new WebResourceLoader({ load: async () => new Uint8Array() }, 0));
  });

  it("rejects malformed and over-budget descriptors before reading the provider", async () => {
    let reads = 0;
    const loader = new WebResourceLoader({ load: async () => { reads += 1; return bytes; } }, bytes.length);
    for (const byteLength of ["01", "-1", "1.0", "", String(bytes.length + 1), "9007199254740993", "9223372036854775808"]) {
      await assert.rejects(loader.loadVerified({ ...request, descriptor: { ...request.descriptor, byteLength } }), /byteLength|maximumResourceBytes/);
    }
    assert.equal(reads, 0);
  });

  it("checks the actual provider bytes against the limit even if the descriptor understates them", async () => {
    const loader = new WebResourceLoader({ load: async () => new Uint8Array(bytes.length + 1) }, bytes.length);
    await assert.rejects(loader.loadVerified(request), /maximumResourceBytes/);
  });
});

describe("WebModuleLoader import ownership", () => {
  it("owns an in-flight activation disposer before close and rejects activation after closing starts", async () => {
    const scope = new Scope();
    let entered!: () => void;
    let release!: () => void;
    const started = new Promise<void>((resolve) => { entered = resolve; });
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const disposed: string[] = [];
    const loader = new WebModuleLoader([WEB_MODULE_CAPABILITY], async () => ({
      activate: async (context) => {
        assert.equal("close" in context.scope, false);
        assert.equal("dispose" in context.scope, false);
        const child = context.scope.child();
        child.effect(() => { disposed.push("child"); });
        await child.close();
        assert.equal(context.scope.closed, false);
        entered(); await gate; return { dispose: () => { disposed.push("activation"); } };
      },
      dispose: () => { disposed.push("module"); },
    }));
    const assignment: Assignment = { pluginId: "p", facetId: "web", runtimeInstanceId: "i", executionTarget: "client:web", entryModule: "entry.js", payloadDigest: request.descriptor.digest,
      requiredCapabilities: [WEB_MODULE_CAPABILITY], resources: [request.descriptor] };
    const context: ClientContext = { scope: scope.view, resources: { load: async () => ({ bytes, byteLength: String(bytes.length) }) }, host: { call: async () => null } };
    const module = await loader.load(assignment, context);
    const activation = Promise.resolve(module.activate!(context));
    const rejected = assert.rejects(activation, /closed/);
    await started;
    let closed = false;
    const closing = scope.close().then(() => { closed = true; });
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    const closedBeforeActivation = closed;
    release();
    await Promise.all([closing, rejected]);
    assert.equal(closedBeforeActivation, false);
    assert.deepEqual(disposed, ["child", "activation", "module"]);
    await scope.close();
    assert.deepEqual(disposed, ["child", "activation", "module"]);
  });

  it("settles a directly throwing activation without leaving scope close waiting", { timeout: 1000 }, async () => {
    const scope = new Scope();
    let disposals = 0;
    const failure = new Error("activate failed");
    const loader = new WebModuleLoader([WEB_MODULE_CAPABILITY], async () => ({
      activate: () => { throw failure; },
      dispose: () => { disposals += 1; },
    }));
    const assignment: Assignment = { pluginId: "p", facetId: "web", runtimeInstanceId: "i", executionTarget: "client:web", entryModule: "entry.js", payloadDigest: request.descriptor.digest,
      requiredCapabilities: [WEB_MODULE_CAPABILITY], resources: [request.descriptor] };
    const context: ClientContext = { scope: scope.view, resources: { load: async () => ({ bytes, byteLength: String(bytes.length) }) }, host: { call: async () => null } };
    const module = await loader.load(assignment, context);
    await assert.rejects(Promise.resolve(module.activate!(context)), (error) => error === failure);
    await scope.close();
    await scope.close();
    assert.equal(disposals, 1);
  });

  it("registers the pending namespace disposer before import and makes close await it exactly once", async () => {
    const scope = new Scope();
    let release!: () => void;
    let entered!: () => void;
    const started = new Promise<void>((resolve) => { entered = resolve; });
    const pending = new Promise<void>((resolve) => { release = resolve; });
    let disposals = 0;
    const loader = new WebModuleLoader([WEB_MODULE_CAPABILITY], async () => {
      entered(); await pending;
      return { activate: () => {}, dispose: () => { disposals += 1; } };
    });
    const assignment: Assignment = { pluginId: "p", facetId: "web", runtimeInstanceId: "i", executionTarget: "client:web", entryModule: "entry.js", payloadDigest: request.descriptor.digest,
      requiredCapabilities: [WEB_MODULE_CAPABILITY], resources: [request.descriptor] };
    const context: ClientContext = { scope: scope.view, resources: { load: async () => ({ bytes, byteLength: String(bytes.length) }) }, host: { call: async () => null } };
    const loading = loader.load(assignment, context);
    const loadOutcome = loading.then(() => "loaded", () => "closed");
    await started;
    let closed = false;
    const closing = scope.close().then(() => { closed = true; });
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    const closedBeforeImport = closed;
    release();
    await closing;
    assert.equal(closedBeforeImport, false);
    assert.equal(await loadOutcome, "closed");
    await scope.close();
    assert.equal(disposals, 1);
  });
});
