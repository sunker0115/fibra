import { expect, test, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { build } from "esbuild";
import { checkSingleFile } from "../build.mjs";
import type { LifecyclePhase, ProtocolEnvelope } from "@sstlfsj/fibra-client-runtime";

function applied(result: ProtocolEnvelope | undefined) {
  expect(result?.type).toBe("client.lifecycle-result");
  if (result?.type !== "client.lifecycle-result") throw new Error("missing lifecycle result");
  expect(result.payload.outcome).toEqual({ kind: "APPLIED" });
}

async function execute(page: Page, phase: LifecyclePhase, id: string, revision: string) {
  applied(await page.evaluate(({ phase, id, revision }) => window.gate.receive(window.gate.command(phase, id, revision)), { phase, id, revision }));
  expect(await page.evaluate((id) => window.gate.runtime.phaseOf(id), id)).toBe({ prepare: "PREPARED", activate: "ACTIVE", drain: "DRAINED", stop: "STOPPED" }[phase]);
}

async function ready(page: Page, query = "") {
  const response = await page.goto(`/${query}`);
  expect(response?.headers()["content-security-policy"]).not.toContain("unsafe-eval");
  await page.waitForFunction(() => window.gate !== undefined);
  return response;
}

test("standard builder and AST reject every runtime dependency form", async () => {
  for (const specifier of ["./relative.js", "react", "https://example.invalid/module.js"]) {
    for (const contents of [`import ${JSON.stringify(specifier)};`, `await import(${JSON.stringify(specifier)});`]) {
      const result = await build({ stdin: { contents }, bundle: true, format: "esm", splitting: false, write: false, metafile: true, external: [specifier] });
      const code = result.outputFiles[0]!.text;
      expect(() => checkSingleFile(code, result.metafile)).toThrow(/runtime import/);
      expect(() => checkSingleFile(code, { outputs: {} })).toThrow(/runtime import/);
    }
  }
  expect(() => checkSingleFile("await import(globalThis.moduleName)", { outputs: {} })).toThrow(/runtime import/);
});

test("Java fixtures, capability handshake, DOM and React share the core lifecycle", async ({ page }) => {
  const response = await ready(page);
  expect(response?.headers()["content-security-policy"]).toContain("script-src 'self' blob:");
  const fixtures = JSON.parse(await readFile(new URL("../../../../fibra-client-protocol/src/main/resources/com/sstlfsj/fibra/client/protocol/v1-fixtures.json", import.meta.url), "utf8"));
  expect(await page.evaluate(() => window.gate.fixtures)).toEqual(fixtures);
  expect(await page.evaluate(() => window.gate.hello.payload.capabilities)).toContain("client.web.module.blob.v1");
  const snapshot = await page.evaluate(() => window.gate.snapshot("1", [["dom", "dom-1"], ["react", "react-1"]]));
  expect(JSON.stringify(snapshot)).not.toMatch(/https?:|"bytes"|"url"/);
  await page.evaluate(() => window.gate.receive(window.gate.snapshot("1", [["dom", "dom-1"], ["react", "react-1"]])));
  for (const id of ["dom-1", "react-1"]) {
    const results = await page.evaluate((id) => {
      const prepare = window.gate.command("prepare", id, "1");
      return Promise.all([window.gate.receive(prepare), window.gate.receive(prepare)]);
    }, id);
    results.forEach(applied);
    expect(await page.evaluate((id) => window.gate.runtime.phaseOf(id), id)).toBe("PREPARED");
    await execute(page, "activate", id, "1");
  }
  await expect(page.getByTestId("dom-probe")).toHaveText("DOM probe");
  await expect(page.getByTestId("react-probe")).toHaveText("React probe");
  await page.evaluate(() => window.dispatchEvent(new Event("probe-ping")));
  expect(await page.evaluate(() => window.probe.listenerCalls)).toBe(2);
  await expect.poll(() => page.evaluate(() => window.probe.timerCalls)).toBeGreaterThan(0);
  for (const id of ["dom-1", "react-1"]) {
    await execute(page, "drain", id, "1");
    await execute(page, "stop", id, "1");
  }
  await expect(page.locator("#mounts")).toBeEmpty();
  const counts = await page.evaluate(() => ({ timers: window.probe.timerCalls, listeners: window.probe.listenerCalls }));
  await page.waitForTimeout(80);
  await page.evaluate(() => window.dispatchEvent(new Event("probe-ping")));
  expect(await page.evaluate(() => ({ timers: window.probe.timerCalls, listeners: window.probe.listenerCalls }))).toEqual(counts);
  expect(await page.evaluate(() => window.probe.createdURLs.length)).toBe(2);
  expect(await page.evaluate(() => window.probe.revokedURLs)).toEqual(await page.evaluate(() => window.probe.createdURLs));
  expect(await page.evaluate(() => window.probe.events.filter((event) => event.endsWith(":activate")))).toEqual(["dom:activate", "react:activate"]);
  expect(await page.evaluate(() => window.probe.events.filter((event) => event.startsWith("dom:") && !event.includes("import")))).toEqual([
    "dom:prepare", "dom:activate", "dom:drain", "dom:stop", "dom:return-dispose", "dom:dispose-start", "dom:dispose-end",
  ]);
  expect(await page.evaluate(() => window.probe.events.filter((event) => event.startsWith("react:")))).toEqual([
    "react:prepare", "react:activate", "react:drain", "react:stop", "react:return-dispose", "react:dispose",
  ]);
});

test("size and digest failures never evaluate or populate the session cache", async ({ page }) => {
  await ready(page);
  for (const corrupt of ["size", "digest"]) {
    await page.route("**/resources/*.js", async (route) => {
      const response = await route.fetch();
      const body = await response.body();
      await route.fulfill({ response, body: corrupt === "size" ? Buffer.alloc(0) : Buffer.alloc(body.length) });
    }, { times: 1 });
    const outcome = await page.evaluate(async (corrupt) => {
      await window.gate.receive(window.gate.snapshot(corrupt === "size" ? "1" : "2", [["dom", corrupt]]));
      return window.gate.receive(window.gate.command("prepare", corrupt, corrupt === "size" ? "1" : "2"));
    }, corrupt);
    expect(outcome.payload.outcome.kind).toBe("FAILED");
    expect(await page.evaluate(() => window.probe.evaluations)).toEqual([]);
    expect(await page.evaluate(() => window.probe.createdURLs)).toEqual([]);
  }
  await page.evaluate(() => window.gate.receive(window.gate.snapshot("3", [["dom", "valid"]])));
  await execute(page, "prepare", "valid", "3");
  expect(await page.evaluate(() => window.probe.evaluations)).toEqual(["dom"]);
  expect(await page.evaluate(() => window.gate.reads)).toBe(3);
});

test("concurrent download coalescing, replacement closure and A→B→A byte reuse", async ({ page }) => {
  await ready(page);
  await page.evaluate(() => window.gate.receive(window.gate.snapshot("1", [["dom", "a1"], ["dom", "a2"]])));
  const prepared = await page.evaluate(() => Promise.all(["a1", "a2"].map((id) => window.gate.receive(window.gate.command("prepare", id, "1")))));
  prepared.forEach(applied);
  for (const id of ["a1", "a2"]) expect(await page.evaluate((id) => window.gate.runtime.phaseOf(id), id)).toBe("PREPARED");
  await execute(page, "activate", "a1", "1");
  await page.evaluate(() => {
    window.gate.blockDisposal();
    window.gate.pending = window.gate.receive(window.gate.snapshot("2", [["react", "b"]]));
  });
  await expect.poll(() => page.evaluate(() => window.probe.events.includes("dom:dispose-start"))).toBe(true);
  expect(await page.evaluate(() => window.gate.runtime.phaseOf("b"))).toBeUndefined();
  expect(await page.evaluate(() => window.gate.reads)).toBe(1);
  await page.evaluate(async () => {
    window.gate.releaseDisposal();
    await window.gate.pending;
  });
  await execute(page, "prepare", "b", "2");
  await execute(page, "activate", "b", "2");
  await page.evaluate(() => window.gate.receive(window.gate.snapshot("3", [["dom", "a3"]])));
  await execute(page, "prepare", "a3", "3");
  await execute(page, "activate", "a3", "3");
  expect(await page.evaluate(() => window.gate.reads)).toBe(2);
  await expect(page.getByTestId("dom-probe")).toHaveText("DOM probe");
  await expect(page.getByTestId("react-probe")).toHaveCount(0);
  const stale = await page.evaluate(() => window.gate.receive(window.gate.command("activate", "a1", "1")));
  expect(stale.payload.outcome.kind).toBe("FAILED");
  expect(await page.evaluate(() => window.gate.runtime.phaseOf("a3"))).toBe("ACTIVE");
  expect(await page.evaluate(() => window.probe.createdURLs.length - window.probe.revokedURLs.length)).toBe(1);
});

test("late provider completion cannot activate a revoked assignment", async ({ page }) => {
  await ready(page);
  let release!: () => void;
  let started!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  const received = new Promise<void>((resolve) => { started = resolve; });
  await page.route("**/resources/*.js", async (route) => { started(); await pending; await route.continue(); }, { times: 1 });
  await page.evaluate(() => {
    window.gate.pending = window.gate.receive(window.gate.snapshot("1", [["dom", "old"]])).then(() => window.gate.receive(window.gate.command("prepare", "old", "1")));
  });
  await received;
  await page.evaluate(() => { window.gate.replacement = window.gate.receive(window.gate.snapshot("2", [["dom", "new"]])); });
  release();
  const late = await page.evaluate(async () => { const outcome = await window.gate.pending; await window.gate.replacement; return outcome; });
  expect(late.payload.outcome.kind).toBe("FAILED");
  expect(await page.evaluate(() => window.probe.evaluations)).toEqual([]);
  await execute(page, "prepare", "new", "2");
  expect(await page.evaluate(() => window.gate.reads)).toBe(1);
});

test("refresh uses a new session and refuses the previous session fence", async ({ page }) => {
  await ready(page);
  const old = await page.evaluate(() => window.gate.command("prepare", "a", "1"));
  await page.reload();
  await page.waitForFunction(() => window.gate !== undefined);
  await page.evaluate(() => window.gate.receive(window.gate.snapshot("1", [["dom", "a"]])));
  const outcome = await page.evaluate((command) => window.gate.receive(command), old);
  expect(outcome.payload.outcome.kind).toBe("FAILED");
  expect(await page.evaluate(() => window.gate.reads)).toBe(0);
});

test("missing blob capability or restrictive CSP rejects loader without widening policy", async ({ page }) => {
  for (const query of ["?no-capability", "?no-blob"]) {
    const response = await ready(page, query);
    if (query === "?no-blob") expect(response?.headers()["content-security-policy"]).not.toContain("blob:");
    expect(await page.evaluate(() => window.gate.hello.payload.capabilities)).not.toContain("client.web.module.blob.v1");
    const outcome = await page.evaluate(async () => {
      await window.gate.receive(window.gate.snapshot("1", [["dom", "a"]]));
      return window.gate.receive(window.gate.command("prepare", "a", "1"));
    });
    expect(outcome.payload.outcome.kind).toBe("FAILED");
    expect(outcome.payload.outcome.failure.message).toContain("client.web.module.blob.v1");
    expect(await page.evaluate(() => window.gate.reads)).toBe(0);
  }
});

test("verification HTTP provider rejects scheme, origin, redirects and omits cookies", async ({ page, context }) => {
  await ready(page);
  await context.addCookies([{ name: "secret", value: "must-not-send", url: "http://127.0.0.1:4179" }]);
  for (const endpoint of ["file:///tmp/x", "https://example.invalid/x", "/redirect"]) {
    expect(await page.evaluate(async (endpoint) => {
      try { await window.gate.providerProbe(endpoint); return "accepted"; } catch { return "rejected"; }
    }, endpoint)).toBe("rejected");
  }
  expect(await page.evaluate(async () => new TextDecoder().decode(await window.gate.providerProbe("/cookie-echo")))).toBe("");
});

for (const renderer of ["dom", "react"] as const) {
  for (const failure of ["module", "effect"] as const) {
    test(`${renderer} ${failure} disposal failure retains its owner and still cleans every other effect`, async ({ page }) => {
      await ready(page);
      await page.evaluate((renderer) => window.gate.receive(window.gate.snapshot("1", [[renderer, "old"]])), renderer);
      for (const phase of ["prepare", "activate", "drain"] as const) await execute(page, phase, "old", "1");
      await page.evaluate(({ renderer, failure }) => { window.probe.failure = { renderer, failure }; }, { renderer, failure });
      const stop = await page.evaluate(() => window.gate.receive(window.gate.command("stop", "old", "1")));
      expect(stop.payload.outcome.kind).toBe("FAILED");
      expect(await page.evaluate(() => window.gate.runtime.phaseOf("old"))).toBe("FAILED");
      await expect(page.locator("#mounts")).toBeEmpty();
      expect(await page.evaluate(() => window.probe.createdURLs)).toEqual(await page.evaluate(() => window.probe.revokedURLs));
      const counts = await page.evaluate(() => ({ timers: window.probe.timerCalls, listeners: window.probe.listenerCalls }));
      await page.waitForTimeout(80);
      await page.evaluate(() => window.dispatchEvent(new Event("probe-ping")));
      expect(await page.evaluate(() => ({ timers: window.probe.timerCalls, listeners: window.probe.listenerCalls }))).toEqual(counts);
      const events = await page.evaluate(() => window.probe.events);
      expect(events.filter((event) => event === `${renderer}:stop`)).toHaveLength(1);
      expect(events.filter((event) => event === `${renderer}:return-dispose`)).toHaveLength(1);
      expect(events.filter((event) => event === (renderer === "dom" ? "dom:dispose-start" : "react:dispose"))).toHaveLength(1);
      expect(events.indexOf(`${renderer}:stop`)).toBeLessThan(events.indexOf(`${renderer}:return-dispose`));
      expect(events.indexOf(`${renderer}:return-dispose`)).toBeLessThan(events.indexOf(renderer === "dom" ? "dom:dispose-start" : "react:dispose"));
      for (let attempt = 0; attempt < 2; attempt += 1) {
        expect(await page.evaluate(async () => {
          try { await window.gate.receive(window.gate.snapshot("2", [["dom", "next"]])); return "replaced"; } catch { return "retained"; }
        })).toBe("retained");
      }
      expect(await page.evaluate(() => window.gate.runtime.actorCount)).toBe(1);
      expect(await page.evaluate(() => window.gate.runtime.phaseOf("next"))).toBeUndefined();
      expect(await page.evaluate(() => window.probe.events)).toEqual(events);
    });
  }
}

test("HTTP data plane rejects advertised and streaming bodies over the execution budget", async ({ page, request }) => {
  await ready(page);
  for (const endpoint of ["/oversized-length", "/oversized-stream"]) {
    expect(await page.evaluate(async (endpoint) => {
      try { await window.gate.providerProbe(endpoint); return "accepted"; } catch (failure) { return String(failure); }
    }, endpoint)).toContain("maximumResourceBytes");
  }
  await expect.poll(async () => (await request.get("/overflow-status")).json()).toMatchObject({ cancelled: true });
});

test("native top-level import cannot prepare or acknowledge after assignment revocation", async ({ page }) => {
  await ready(page);
  await page.evaluate(async () => {
    window.gate.blockImport(); window.gate.blockDisposal();
    await window.gate.receive(window.gate.snapshot("1", [["dom", "old"]]));
    window.gate.pending = window.gate.receive(window.gate.command("prepare", "old", "1"));
  });
  await expect.poll(() => page.evaluate(() => window.probe.events.includes("dom:import-start"))).toBe(true);
  await page.evaluate(() => { window.gate.replacement = window.gate.receive(window.gate.snapshot("2", [["dom", "new"]])); });
  await page.evaluate(() => window.gate.releaseImport());
  const result = await page.evaluate(() => window.gate.pending);
  expect(result.payload.outcome.kind).toBe("FAILED");
  await expect.poll(() => page.evaluate(() => window.probe.events.includes("dom:dispose-start"))).toBe(true);
  expect(await page.evaluate(() => window.probe.events.includes("dom:prepare"))).toBe(false);
  expect(await page.evaluate(() => window.gate.runtime.phaseOf("old"))).toBe("FAILED");
  expect(await page.evaluate(() => window.gate.runtime.phaseOf("new"))).toBeUndefined();
  await page.evaluate(async () => { window.gate.releaseDisposal(); await window.gate.replacement; });
  await execute(page, "prepare", "new", "2");
  expect(await page.evaluate(() => window.gate.reads)).toBe(1);
});
