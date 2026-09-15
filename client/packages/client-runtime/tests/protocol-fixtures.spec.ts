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

  it("distinguishes raw protocol version tokens and hello identity failures", () => {
    const hello = '{"protocolVersion":VERSION,"messageId":"one","type":"client.hello","payload":{"identity":{"clientNonce":"nonce"},"executionTarget":"client:web","capabilities":[]}}';
    for (const version of ['"1"', "1.0"]) {
      assert.throws(() => decodeEnvelope(hello.replace("VERSION", version)), (error: unknown) => {
        assert(error instanceof ClientProtocolError);
        assert.equal(error.code, "MALFORMED_MESSAGE");
        return true;
      });
    }
    assert.throws(() => decodeEnvelope(hello.replace("VERSION", "2")), (error: unknown) => {
      assert(error instanceof ClientProtocolError);
      assert.equal(error.code, "UNSUPPORTED_PROTOCOL");
      return true;
    });
    assert.throws(() => decodeEnvelope(hello.replace("VERSION", "1").replace('"clientNonce":"nonce"', '"clientNonce":"nonce","extra":true')), (error: unknown) => {
      assert(error instanceof ClientProtocolError);
      assert.equal(error.code, "INVALID_IDENTITY");
      return true;
    });
  });

  it("matches Java literal depth, decimal scale, base64 and URI rules", () => {
    const template = '{"protocolVersion":1,"messageId":"one","type":"client.call","payload":{"call":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"expectedViewRevision":"0","registrationIdentity":"1"},"contributionKind":"tool","contributionId":{"providerInstanceId":"provider","localName":"read"},"input":INPUT}}';
    const nested = (count: number) => "[".repeat(count) + "null" + "]".repeat(count);
    const nestedObject = (count: number) => {
      let value = "null";
      for (let index = 0; index < count; index += 1) value = `{"kind":"OBJECT","values":{"value":${value}}}`;
      return value;
    };
    assert.doesNotThrow(() => decodeEnvelope(template.replace("INPUT", nested(64))));
    assert.throws(() => decodeEnvelope(template.replace("INPUT", nested(65))), ClientProtocolError);
    assert.doesNotThrow(() => decodeEnvelope(template.replace("INPUT", nestedObject(32))));
    assert.throws(() => decodeEnvelope(template.replace("INPUT", nestedObject(33))), ClientProtocolError);
    assert.throws(() => decodeEnvelope(template.replace("INPUT", '{"kind":"NUMBER","value":"1E-2147483648"}')), ClientProtocolError);
    assert.doesNotThrow(() => decodeEnvelope(template.replace("INPUT", '{"kind":"NUMBER","value":"1E+2147483648"}')));

    const snapshot = '{"protocolVersion":1,"messageId":"one","type":"host.snapshot","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"},"viewRevision":"0","targetRevision":"1","targetDigest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","assignments":[{"pluginId":"plugin","facetId":"facet","runtimeInstanceId":"runtime","executionTarget":"client:web","entryModule":"index.js","payloadDigest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","requiredCapabilities":[],"resources":[{"path":"index.js","digest":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","content":CONTENT}]}],"contributions":[]}}';
    assert.doesNotThrow(() => decodeEnvelope(snapshot.replace("CONTENT", '{"kind":"BYTES","base64":"YQ"}')));
    assert.doesNotThrow(() => decodeEnvelope(snapshot.replace("CONTENT", '{"kind":"BYTES","base64":"YQ=="}')));
    assert.throws(() => decodeEnvelope(snapshot.replace("CONTENT", '{"kind":"URL","url":"https://example.test/a b"}')), ClientProtocolError);
  });
});
