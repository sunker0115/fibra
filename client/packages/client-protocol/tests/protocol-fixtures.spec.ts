import { readFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";

import type {
  Assignment,
  ClientEntryModule,
  ClientInstanceContext,
  ClientModule,
} from "@sstlfsj/fibra-client-api";
import { ClientProtocolError, decodeEnvelope, decodeFixtures, encodeEnvelope } from "../src/index.js";

const fixturesUrl = new URL(
  "../../../../fibra-client-protocol/src/main/resources/com/sstlfsj/fibra/client/protocol/v1-fixtures.json",
  import.meta.url,
);

describe("v1 protocol codec", () => {
  it("round-trips the Java v1 fixtures without changing fields or values", async () => {
    const wire = await readFile(fileURLToPath(fixturesUrl), "utf8");
    const fixtures = JSON.parse(wire) as unknown[];
    const decoded = decodeFixtures(wire);

    assert.equal(decoded.length, 12);
    assert.deepEqual(decoded.map((message) => message.type), [
      "client.hello", "host.welcome", "host.snapshot", "host.prepare", "host.activate",
      "host.drain", "host.stop", "client.lifecycle-result", "client.observed", "client.call",
      "host.call-result", "client.detach",
    ]);
    assert.deepEqual(decoded.map((message) => JSON.parse(encodeEnvelope(message))), fixtures);
  });

  it("uses unitTargetRevision for lifecycle and observations while snapshots retain targetRevision", () => {
    const lifecycle = '{"protocolVersion":1,"messageId":"one","type":"host.prepare","payload":{"lifecycle":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"unitTargetRevision":"5","runtimeInstanceId":"runtime","lifecycleOperationId":"operation"}}}';
    const observed = '{"protocolVersion":1,"messageId":"one","type":"client.observed","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"executions":[{"unitTargetRevision":"5","runtimeInstanceId":"runtime","lifecycleOperationId":"operation","state":"ACTIVE"}]}}';

    assert.doesNotThrow(() => decodeEnvelope(lifecycle));
    assert.doesNotThrow(() => decodeEnvelope(observed));
    assert.throws(() => decodeEnvelope(lifecycle.replace("unitTargetRevision", "targetRevision")), ClientProtocolError);
    assert.throws(() => decodeEnvelope(observed.replace("unitTargetRevision", "targetRevision")), ClientProtocolError);
  });

  it("rejects non-canonical identity tokens, duplicate fields, and mixed phase identities", () => {
    const lifecycle = '{"protocolVersion":1,"messageId":"one","type":"host.prepare","payload":{"lifecycle":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"unitTargetRevision":"01","runtimeInstanceId":"runtime","lifecycleOperationId":"operation"}}}';
    assert.throws(() => decodeEnvelope(lifecycle), ClientProtocolError);
    assert.throws(() => decodeEnvelope('{"protocolVersion":1,"protocolVersion":1,"messageId":"one","type":"client.detach","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"}}}'), ClientProtocolError);
    assert.throws(() => decodeEnvelope('{"protocolVersion":1,"messageId":"one","type":"client.detach","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"lifecycle":{}}}'), ClientProtocolError);
  });

  it("accepts tagged literals but rejects native JSON numbers, excess fields, and invalid protocol versions", () => {
    const call = '{"protocolVersion":VERSION,"messageId":"one","type":"client.call","payload":{"call":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"expectedViewRevision":"0","registrationIdentity":"1"},"contributionKind":"tool","contributionId":{"providerInstanceId":"provider","localName":"read"},"input":INPUT}}';

    assert.deepEqual((decodeEnvelope(call.replace("VERSION", "1").replace("INPUT", '{"kind":"NUMBER","value":"1E+400"}')) as { payload: { input: unknown } }).payload.input,
      { kind: "NUMBER", value: "1E+400" });
    assert.throws(() => decodeEnvelope(call.replace("VERSION", "1").replace("INPUT", "1")), ClientProtocolError);
    assert.throws(() => decodeEnvelope(call.replace("VERSION", "1").replace("INPUT", '{"kind":"NUMBER","value":"1","extra":true}')), ClientProtocolError);
    for (const version of ['"1"', "1.0", "1e0", "2147483648"]) {
      assert.throws(() => decodeEnvelope(call.replace("VERSION", version).replace("INPUT", "null")), ClientProtocolError);
    }
    assert.throws(() => decodeEnvelope(call.replace("VERSION", "2").replace("INPUT", "null")), (error: unknown) => {
      assert(error instanceof ClientProtocolError);
      assert.equal(error.code, "UNSUPPORTED_PROTOCOL");
      return true;
    });
  });

  it("keeps resource descriptor validation structural and transport-neutral", () => {
    const snapshot = '{"protocolVersion":1,"messageId":"one","type":"host.snapshot","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"viewRevision":"0","targetRevision":"1","targetDigest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","assignments":[{"pluginId":"plugin","facetId":"facet","desiredEntryId":"entry","definitionId":"definition","runtimeInstanceId":"runtime","unitTargetRevision":"1","executionTarget":"client:web","config":{"kind":"OBJECT","values":{"mode":"one"}},"entryModule":"index.js","payloadDigest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","requiredCapabilities":[],"resources":[{"path":"index.js","digest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","byteLength":"0"}]}],"contributions":[]}}';
    assert.doesNotThrow(() => decodeEnvelope(snapshot));
    assert.throws(() => decodeEnvelope(snapshot.replace('"byteLength":"0"', '"byteLength":"01"')), ClientProtocolError);
    assert.throws(() => decodeEnvelope(snapshot.replace('"path":"index.js"', '"path":"../index.js"')), ClientProtocolError);
    for (const path of ["file:secret", "C:/secret", "https:secret"]) {
      assert.throws(() => decodeEnvelope(snapshot.replace('"path":"index.js"', `"path":"${path}"`)), ClientProtocolError);
    }
    assert.throws(() => decodeEnvelope(snapshot.replace('"entryModule":"index.js"', '"entryModule":"missing.js"')), ClientProtocolError);
  });

  it("preserves distinct entry identity and resolved config for assignments sharing one facet", () => {
    const base = '{"protocolVersion":1,"messageId":"one","type":"host.snapshot","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"viewRevision":"0","targetRevision":"5","targetDigest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","assignments":ASSIGNMENTS,"contributions":[]}}';
    const assignment = (entry: string, runtime: string, mode: string) => `{"pluginId":"plugin","facetId":"client","desiredEntryId":"${entry}","definitionId":"main","runtimeInstanceId":"${runtime}","unitTargetRevision":"5","executionTarget":"client:web","config":{"kind":"OBJECT","values":{"mode":"${mode}"}},"entryModule":"index.js","payloadDigest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","requiredCapabilities":[],"resources":[{"path":"index.js","digest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","byteLength":"0"}]}`;
    const wire = base.replace("ASSIGNMENTS", `[${assignment("first", "runtime-first", "one")},${assignment("second", "runtime-second", "two")}]`);
    const decoded = decodeEnvelope(wire);

    assert.equal(encodeEnvelope(decoded), wire);
    assert.equal(decoded.type, "host.snapshot");
    if (decoded.type !== "host.snapshot") throw new Error("snapshot expected");
    assert.deepEqual(decoded.payload.assignments.map((value) => [value.desiredEntryId, value.definitionId, value.config]), [
      ["first", "main", { kind: "OBJECT", values: { mode: "one" } }],
      ["second", "main", { kind: "OBJECT", values: { mode: "two" } }],
    ]);
  });

  it("rebuilds isolated client instances from each decoded snapshot without private assignment fields", async () => {
    const created: ClientInstanceContext[] = [];
    const entryModule: ClientEntryModule = {
      definitions: [{
        definitionId: "main",
        create(context) {
          created.push(context);
          return { activate() {} };
        },
      }],
    };
    const wire = (clientExecutionId: string, suffix: string) => JSON.stringify({
      protocolVersion: 1,
      messageId: `snapshot-${suffix}`,
      type: "host.snapshot",
      payload: {
        session: { hostInstanceId: "host", clientExecutionId },
        viewRevision: suffix,
        targetRevision: "9",
        targetDigest: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        assignments: [assignment("first", `runtime-first-${suffix}`, "one"),
          assignment("second", `runtime-second-${suffix}`, "two")],
        contributions: [],
      },
    });
    const assignment = (desiredEntryId: string, runtimeInstanceId: string,
      mode: string): Assignment => ({
      pluginId: "plugin",
      facetId: "client",
      desiredEntryId,
      definitionId: "main",
      runtimeInstanceId,
      unitTargetRevision: "7",
      executionTarget: "client:web",
      config: { kind: "OBJECT", values: { mode } },
      entryModule: "index.js",
      payloadDigest: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      requiredCapabilities: [],
      resources: [{
        path: "index.js",
        digest: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        byteLength: "0",
      }],
    });
    const instantiate = async (encoded: string): Promise<ClientModule[]> => {
      const decoded = decodeEnvelope(encoded);
      if (decoded.type !== "host.snapshot") throw new Error("snapshot expected");
      return Promise.all(decoded.payload.assignments.map(async (value) => {
        const definitions = entryModule.definitions.filter(definition =>
          definition.definitionId === value.definitionId);
        if (definitions.length !== 1) throw new Error("definition must match exactly once");
        return definitions[0]!.create({
          desiredEntryId: value.desiredEntryId,
          definitionId: value.definitionId,
          unitTargetRevision: value.unitTargetRevision,
          runtimeInstanceId: value.runtimeInstanceId,
          config: value.config,
          scope: {} as ClientInstanceContext["scope"],
          host: {} as ClientInstanceContext["host"],
        });
      }));
    };

    const firstSession = await instantiate(wire("client-one", "one"));
    const secondSession = await instantiate(wire("client-two", "two"));

    assert.equal(firstSession.length, 2);
    assert.equal(secondSession.length, 2);
    assert.notEqual(firstSession[0], secondSession[0]);
    assert.deepEqual(created.map(context => [context.desiredEntryId,
      context.runtimeInstanceId, context.unitTargetRevision, context.config]), [
      ["first", "runtime-first-one", "7", { kind: "OBJECT", values: { mode: "one" } }],
      ["second", "runtime-second-one", "7", { kind: "OBJECT", values: { mode: "two" } }],
      ["first", "runtime-first-two", "7", { kind: "OBJECT", values: { mode: "one" } }],
      ["second", "runtime-second-two", "7", { kind: "OBJECT", values: { mode: "two" } }],
    ]);
  });

  it("enforces literal depth and decimal scale bounds", () => {
    const call = '{"protocolVersion":1,"messageId":"one","type":"client.call","payload":{"call":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"expectedViewRevision":"0","registrationIdentity":"1"},"contributionKind":"tool","contributionId":{"providerInstanceId":"provider","localName":"read"},"input":INPUT}}';
    const nested = (count: number) => "[".repeat(count) + "null" + "]".repeat(count);
    const nestedObject = (count: number) => {
      let value = "null";
      for (let index = 0; index < count; index += 1) value = `{"kind":"OBJECT","values":{"value":${value}}}`;
      return value;
    };

    assert.doesNotThrow(() => decodeEnvelope(call.replace("INPUT", nested(62))));
    assert.throws(() => decodeEnvelope(call.replace("INPUT", nested(63))), ClientProtocolError);
    assert.doesNotThrow(() => decodeEnvelope(call.replace("INPUT", nestedObject(31))));
    assert.throws(() => decodeEnvelope(call.replace("INPUT", nestedObject(32))), ClientProtocolError);
    assert.throws(() => decodeEnvelope(call.replace("INPUT", '{"kind":"NUMBER","value":"1E-2147483648"}')), ClientProtocolError);
    assert.doesNotThrow(() => decodeEnvelope(call.replace("INPUT", '{"kind":"NUMBER","value":"1E+2147483648"}')));
  });
});
