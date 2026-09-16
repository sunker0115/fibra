import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { ResourceDescriptor, VerifiedResource } from "@sstlfsj/fibra-client-api";
import { VerifiedResourceCache } from "../src/resource-cache.js";

const digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
const descriptor = (byteLength = "0"): ResourceDescriptor => ({ path: "index.js", digest, byteLength });
const empty: VerifiedResource = { bytes: new Uint8Array(), byteLength: "0" };

describe("VerifiedResourceCache", () => {
  it("shares one verified load by digest and validates each descriptor metadata", async () => {
    const cache = new VerifiedResourceCache();
    let loads = 0;
    const load = async () => { loads += 1; return empty; };
    const [first, second] = await Promise.all([
      cache.loadVerified(descriptor(), load),
      cache.loadVerified({ ...descriptor(), path: "nested/index.js" }, load),
    ]);

    assert.equal(first, second);
    assert.equal(loads, 1);
    await assert.rejects(cache.loadVerified(descriptor("1"), load), /byteLength/);
  });

  it("removes failed pending loads so a later request retries", async () => {
    const cache = new VerifiedResourceCache();
    let loads = 0;
    await assert.rejects(cache.loadVerified(descriptor(), async () => {
      loads += 1;
      throw new Error("bad payload");
    }), /bad payload/);
    await cache.loadVerified(descriptor(), async () => { loads += 1; return empty; });
    assert.equal(loads, 2);
  });
});
