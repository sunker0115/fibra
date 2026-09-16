import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { Scope, decodeEnvelope } from "@sstlfsj/fibra-client-runtime";

describe("package exports", () => {
  it("loads compiled runtime exports instead of TypeScript source", async () => {
    const scope = new Scope();
    await scope.close();
    assert.equal(scope.closed, true);
    assert.equal(decodeEnvelope('{"protocolVersion":1,"messageId":"one","type":"client.detach","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"}}}').type, "client.detach");
  });
});
