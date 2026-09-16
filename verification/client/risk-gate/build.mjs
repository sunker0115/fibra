import { build } from "esbuild";
import ts from "typescript";
import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile, copyFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

export function checkSingleFile(code, metafile) {
  for (const output of Object.values(metafile.outputs)) {
    if (output.imports.length !== 0) throw new Error("single-file ESM contains a runtime import (metafile)");
  }
  const source = ts.createSourceFile("entry.js", code, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  const visit = (node) => {
    if (ts.isImportDeclaration(node)
      || (ts.isExportDeclaration(node) && node.moduleSpecifier !== undefined)
      || (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword)) {
      throw new Error("single-file ESM contains a runtime import (TypeScript AST)");
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
}

async function buildGate() {
  await mkdir("target/resources", { recursive: true });
  const descriptors = {};
  for (const name of ["dom", "react"]) {
    const result = await build({
      entryPoints: [`fixtures/${name}-plugin/index.${name === "react" ? "tsx" : "ts"}`],
      outfile: `target/${name}.js`, bundle: true, format: "esm", splitting: false,
      platform: "browser", target: "es2022", write: false, metafile: true,
      define: { "process.env.NODE_ENV": '"production"' },
    });
    const output = result.outputFiles[0];
    checkSingleFile(output.text, result.metafile);
    const digest = createHash("sha256").update(output.contents).digest("hex");
    descriptors[name] = { path: "index.js", digest, byteLength: String(output.contents.byteLength) };
    await writeFile(`target/resources/${digest}.js`, output.contents);
    await writeFile(`target/${name}.meta.json`, JSON.stringify(result.metafile, null, 2));
  }
  await writeFile("target/descriptors.json", JSON.stringify(descriptors));
  await build({ entryPoints: ["src/protocol-harness.ts"], outfile: "target/harness.js", bundle: true, format: "esm", platform: "browser", target: "es2022" });
  await copyFile("src/index.html", "target/index.html");
  await writeFile("target/fixtures.json", await readFile("../../../fibra-client-protocol/src/main/resources/com/sstlfsj/fibra/client/protocol/v1-fixtures.json"));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await buildGate();
