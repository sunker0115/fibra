import { createServer } from "node:http";
import { readFile } from "node:fs/promises";

let overflowStatus = { cancelled: false };

createServer(async (request, response) => {
  const url = new URL(request.url, "http://127.0.0.1:4179");
  response.setHeader("Content-Security-Policy", `default-src 'self'; script-src 'self'${url.searchParams.has("no-blob") ? "" : " blob:"}; connect-src 'self'; object-src 'none'; base-uri 'none'`);
  response.setHeader("Cache-Control", "no-store");
  if (url.pathname === "/overflow-status") { response.end(JSON.stringify(overflowStatus)); return; }
  if (url.pathname === "/oversized-length") {
    response.setHeader("Content-Length", String(2 * 1024 * 1024)); response.end(Buffer.alloc(2 * 1024 * 1024)); return;
  }
  if (url.pathname === "/oversized-stream") {
    overflowStatus = { cancelled: false };
    let chunks = 0;
    const timer = setInterval(() => {
      response.write(Buffer.alloc(64 * 1024)); chunks += 1;
      if (chunks === 32) { clearInterval(timer); response.end(); }
    }, 5);
    response.on("close", () => { clearInterval(timer); overflowStatus.cancelled = chunks < 32; });
    return;
  }
  if (url.pathname === "/redirect") {
    response.writeHead(302, { Location: "http://localhost:4179/cookie-echo" }); response.end(); return;
  }
  if (url.pathname === "/cookie-echo") { response.end(request.headers.cookie ?? ""); return; }
  const path = url.pathname === "/" ? "index.html" : url.pathname.slice(1);
  if (!/^(index\.html|harness\.js|descriptors\.json|fixtures\.json|resources\/[a-f0-9]{64}\.js)$/.test(path)) {
    response.writeHead(404); response.end(); return;
  }
  try {
    response.setHeader("Content-Type", path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "text/javascript" : "application/json");
    response.end(await readFile(new URL(`./target/${path}`, import.meta.url)));
  } catch {
    response.writeHead(404); response.end();
  }
}).listen(4179, "127.0.0.1");
