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

    assert.deepEqual(first, second);
    assert.notEqual(first.bytes, second.bytes);
    assert.equal(loads, 1);
    await assert.rejects(cache.loadVerified(descriptor("1"), load), /byteLength/);
  });

  it("isolates cache-owned bytes from loader and concurrent consumer mutations", async () => {
    const cache = new VerifiedResourceCache();
    const source = new Uint8Array([1, 2, 3]);
    let loads = 0;
    const load = async () => { loads += 1; return { bytes: source, byteLength: "3" }; };
    const [first, concurrent] = await Promise.all([
      cache.loadVerified(descriptor("3"), load), cache.loadVerified(descriptor("3"), load),
    ]);
    source.fill(7);
    first.bytes.fill(9);
    assert.deepEqual(concurrent.bytes, new Uint8Array([1, 2, 3]));
    concurrent.bytes.fill(8);
    assert.deepEqual((await cache.loadVerified(descriptor("3"), load)).bytes, new Uint8Array([1, 2, 3]));
    assert.equal(loads, 1);
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
