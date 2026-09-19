import { readFile, readdir } from "node:fs/promises";
import { join, normalize, relative } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const root = fileURLToPath(new URL("..", import.meta.url));
const corePackages = ["client-api", "client-protocol"];
const rendererNeutralPackages = corePackages;
const allowedWorkspaceDependencies = {
  "client-api": [],
  "client-protocol": ["@sstlfsj/fibra-client-api"],
};
const forbidden = /\b(?:document|window|HTMLElement|fetch|URL|Blob)\b|from\s+["'](?:react|react-dom|electron|vue)["']/;

for (const [name, allowed] of Object.entries(allowedWorkspaceDependencies)) {
  const directory = join(root, "packages", name);
  const packageJson = JSON.parse(await readFile(join(directory, "package.json"), "utf8"));
  for (const field of ["dependencies", "peerDependencies", "optionalDependencies"]) {
    const dependencies = packageJson[field] ?? {};
    for (const dependency of Object.keys(dependencies)) {
      if (dependency.startsWith("@sstlfsj/fibra-client-") && !allowed.includes(dependency)) {
        throw new Error(`${name} has an invalid workspace dependency ${field}.${dependency}`);
      }
      if (rendererNeutralPackages.includes(name) && !allowed.includes(dependency)) {
        throw new Error(`${name} must not declare ${field}.${dependency}`);
      }
    }
  }
  for (const entry of await readdir(join(directory, "src"), { recursive: true }).catch(() => [])) {
    if (typeof entry !== "string" || (!entry.endsWith(".ts") && !entry.endsWith(".tsx"))) continue;
    const sourcePath = join(directory, "src", entry);
    const source = await readFile(sourcePath, "utf8");
    if (corePackages.includes(name) && forbidden.test(source)) throw new Error(`${name}/src/${entry} crosses a platform boundary`);
    const parsed = ts.createSourceFile(sourcePath, source, ts.ScriptTarget.ES2022, true,
      entry.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    const visit = (node) => {
      let specifier;
      if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier !== undefined && ts.isStringLiteral(node.moduleSpecifier)) specifier = node.moduleSpecifier.text;
      if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword && ts.isStringLiteral(node.arguments[0])) specifier = node.arguments[0].text;
      if (specifier !== undefined) validateImport(name, sourcePath, specifier);
      ts.forEachChild(node, visit);
    };
    visit(parsed);
  }
}

function validateImport(owner, sourcePath, specifier) {
  if (specifier.startsWith(".")) {
    const target = normalize(join(sourcePath, "..", specifier));
    for (const packageName of Object.keys(allowedWorkspaceDependencies)) {
      const packageRoot = normalize(join(root, "packages", packageName));
      const packageRelative = relative(packageRoot, target);
      if (packageRelative !== "" && !packageRelative.startsWith("..") && !packageRelative.startsWith("../") && packageName !== owner) {
        throw new Error(`${owner} must not cross packages through a relative import`);
      }
    }
    return;
  }
  if (specifier.startsWith("@sstlfsj/fibra-client-") && !allowedWorkspaceDependencies[owner].includes(specifier)) {
    throw new Error(`${owner} imports forbidden workspace package ${specifier}`);
  }
  if (rendererNeutralPackages.includes(owner) && /^(react|react-dom|electron|vue)(\/|$)/.test(specifier)) {
    throw new Error(`${owner} imports renderer package ${specifier}`);
  }
}
