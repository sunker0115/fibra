import type {
  Assignment, ClientContext, ClientDisposable, ClientModule, ClientResourceProvider,
  ClientResourceRequest, VerifiedResource, VerifiedResourceLoader,
} from "@sstlfsj/fibra-client-api";

export const WEB_MODULE_CAPABILITY = "client.web.module.blob.v1";

/** Used inside the session cache's single-flight callback: reading and verification are inseparable. */
export class WebResourceLoader implements VerifiedResourceLoader {
  constructor(private readonly provider: ClientResourceProvider, private readonly maximumResourceBytes: number) {
    if (!Number.isSafeInteger(maximumResourceBytes) || maximumResourceBytes < 0) {
      throw new Error("maximumResourceBytes must be a non-negative safe integer");
    }
  }

  async loadVerified(request: ClientResourceRequest): Promise<VerifiedResource> {
    if (!/^(0|[1-9][0-9]*)$/.test(request.descriptor.byteLength)) throw new Error("resource byteLength must be canonical");
    if (BigInt(request.descriptor.byteLength) > BigInt(this.maximumResourceBytes)) throw new Error("resource exceeds maximumResourceBytes");
    const loaded = await this.provider.load(request);
    if (loaded.byteLength > this.maximumResourceBytes) throw new Error("resource exceeds maximumResourceBytes");
    const bytes = Uint8Array.from(loaded);
    if (String(bytes.byteLength) !== request.descriptor.byteLength) {
      throw new Error("resource byteLength mismatch");
    }
    const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    const digest = Array.from(hash, (value) => value.toString(16).padStart(2, "0")).join("");
    if (digest !== request.descriptor.digest) throw new Error("resource SHA-256 mismatch");
    return { bytes, byteLength: String(bytes.byteLength) };
  }
}

type Disposer = ClientDisposable | (() => void | Promise<void>);
interface WebModuleExports extends Omit<ClientModule, "activate"> {
  activate(context: ClientContext): void | Disposer | Promise<void | Disposer>;
  dispose?: () => void | Promise<void>;
}

/** Probe the actual page CSP before advertising the blob-module capability. */
export async function supportsWebModules(): Promise<boolean> {
  const url = URL.createObjectURL(new Blob(["export {};"], { type: "text/javascript" }));
  try {
    await import(url);
    return true;
  } catch {
    return false;
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** Stateless browser adapter; assignment authorization, caching and lifecycle belong to core. */
export class WebModuleLoader {
  constructor(
    private readonly capabilities: readonly string[],
    private readonly importModule: (url: string) => Promise<WebModuleExports> = (url) => import(url),
  ) {}

  async load(assignment: Assignment, context: ClientContext): Promise<ClientModule> {
    if (!this.capabilities.includes(WEB_MODULE_CAPABILITY)
      || assignment.requiredCapabilities.some((capability) => !this.capabilities.includes(capability))) {
      throw new Error(`required capability unavailable: ${WEB_MODULE_CAPABILITY}`);
    }
    const resource = await context.resources.load(assignment.entryModule);
    if (context.scope.closed) throw new Error("client scope is closed");
    const url = URL.createObjectURL(new Blob([Uint8Array.from(resource.bytes)], { type: "text/javascript" }));
    const ownedUrl = context.scope.effect(() => URL.revokeObjectURL(url));
    let settle!: (module: WebModuleExports | undefined) => void;
    const namespace = new Promise<WebModuleExports | undefined>((resolve) => { settle = resolve; });
    const ownedModule = context.scope.effect(async () => { await (await namespace)?.dispose?.(); });
    let module: WebModuleExports;
    try {
      module = await this.importModule(url);
      settle(module);
      if (context.scope.closed) throw new Error("client scope is closed");
      if (typeof module.activate !== "function") throw new Error("Web module must export activate(ClientContext)");
    } catch (failure) {
      settle(undefined);
      try { await ownedModule.dispose(); } finally { await ownedUrl.dispose(); }
      throw new Error(`Web module load failed (${WEB_MODULE_CAPABILITY}; check CSP and single-file ESM)`, { cause: failure });
    }
    return {
      prepare: module.prepare?.bind(module),
      activate: async (activeContext) => {
        let settleActivation!: (disposer: Disposer | void) => void;
        const activation = new Promise<Disposer | void>((resolve) => { settleActivation = resolve; });
        const ownedActivation = activeContext.scope.effect(async () => {
          const disposer = await activation;
          if (typeof disposer === "function") await disposer();
          else await disposer?.dispose();
        });
        try {
          settleActivation(await module.activate(activeContext));
          if (activeContext.scope.closed) throw new Error("client scope is closed");
        } catch (failure) {
          settleActivation(undefined);
          await ownedActivation.dispose();
          throw failure;
        }
      },
      drain: module.drain?.bind(module),
      stop: module.stop?.bind(module),
    };
  }
}
