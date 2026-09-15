import { readFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";

import { ClientProtocolError, decodeEnvelope, decodeFixtures, encodeEnvelope } from "../src/protocol.js";

const fixturesUrl = new URL(
  "../../../../fibra-client-protocol/src/main/resources/com/sstlfsj/fibra/client/protocol/v1-fixtures.json",
  import.meta.url,
);

describe("v1 protocol codec", () => {
  it("reads the Java v1 fixtures without changing fields or values", async () => {
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

  it("requires canonical positive decimal strings for identity tokens", () => {
    const fixture = '{"protocolVersion":1,"messageId":"one","type":"host.prepare","payload":{"lifecycle":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"targetRevision":"01","runtimeInstanceId":"runtime","lifecycleOperationId":"operation"}}}';
    assert.throws(() => decodeEnvelope(fixture), ClientProtocolError);
  });

  it("accepts tagged literals but rejects native JSON numbers and excess fields", () => {
    const template = '{"protocolVersion":1,"messageId":"one","type":"client.call","payload":{"call":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"expectedViewRevision":"0","registrationIdentity":"1"},"contributionKind":"tool","contributionId":{"providerInstanceId":"provider","localName":"read"},"input":INPUT}}';

    assert.deepEqual((decodeEnvelope(template.replace("INPUT", '{"kind":"NUMBER","value":"1E+400"}')) as { payload: { input: unknown } }).payload.input,
      { kind: "NUMBER", value: "1E+400" });
    assert.throws(() => decodeEnvelope(template.replace("INPUT", "1")), ClientProtocolError);
    assert.throws(() => decodeEnvelope(template.replace("INPUT", '{"kind":"NUMBER","value":"1","extra":true}')), ClientProtocolError);
  });

  it("rejects duplicate envelope fields and mixed phase identities", () => {
    assert.throws(() => decodeEnvelope('{"protocolVersion":1,"protocolVersion":1,"messageId":"one","type":"client.detach","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"}}}'), ClientProtocolError);
    assert.throws(() => decodeEnvelope('{"protocolVersion":1,"messageId":"one","type":"client.detach","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"lifecycle":{}}}'), ClientProtocolError);
  });
});
