import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, it } from "node:test";

describe("client API publication boundary", () => {
  it("exports contracts only and no resource transport, loader, cache, or runner API", async () => {
    const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
    const declarations = await readFile(new URL("../dist/index.d.ts", import.meta.url), "utf8");
    const implementation = await readFile(new URL("../dist/index.js", import.meta.url), "utf8");

    assert.deepEqual(packageJson.dependencies ?? {}, {});
    assert.deepEqual(packageJson.peerDependencies ?? {}, {});
    assert.deepEqual(packageJson.optionalDependencies ?? {}, {});
    for (const forbidden of ["ClientResourceProvider", "ClientResourceRequest", "VerifiedResource", "ResourceLoader", "Cache", "Runner", "HTMLElement", "React"]) {
      assert.equal(declarations.includes(forbidden), false, `${forbidden} must remain product-owned`);
    }
    for (const forbidden of ["createClientModuleInstance", "resolveClientDefinition", "fetch", "document", "window"]) {
      assert.equal(implementation.includes(forbidden), false, `${forbidden} must remain product-owned`);
    }
  });
});
