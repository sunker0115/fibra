/** Framework-neutral contracts shared by client implementations. */

export interface ClientDisposable {
  dispose(): void | Promise<void>;
}

/** Plugin-facing registration view; only the runtime owns the root lifetime. */
export interface ClientScope {
  readonly closed: boolean;
  child(): OwnedClientScope;
  effect(cleanup: ClientDisposable | (() => void | Promise<void>)): ClientDisposable;
  listen(register: () => ClientDisposable | (() => void | Promise<void>)): ClientDisposable;
  timer(register: () => ClientDisposable | (() => void | Promise<void>)): ClientDisposable;
}

/** A child lifetime created and owned by the caller. */
export interface OwnedClientScope extends ClientScope, ClientDisposable {
  close(): Promise<void>;
}

export interface ClientError {
  readonly code: "MALFORMED_MESSAGE" | "INVALID_IDENTITY" | "UNSUPPORTED_PROTOCOL" | "STALE_OPERATION" | string;
  readonly message: string;
  readonly diagnostics: Readonly<Record<string, string>>;
}

export interface SessionFence {
  readonly hostInstanceId: string;
  readonly clientExecutionId: string;
}

export interface LifecycleFence {
  readonly session: SessionFence;
  readonly targetRevision: string;
  readonly runtimeInstanceId: string;
  readonly lifecycleOperationId: string;
}

export interface CallFence {
  readonly session: SessionFence;
  readonly expectedViewRevision: string;
  readonly registrationIdentity: string;
}

export interface ContributionId {
  readonly providerInstanceId: string;
  readonly localName: string;
}

export type LiteralValue = null | boolean | string | readonly LiteralValue[] | LiteralNumber | LiteralObject;

export interface LiteralNumber {
  readonly kind: "NUMBER";
  readonly value: string;
}

export interface LiteralObject {
  readonly kind: "OBJECT";
  readonly values: Readonly<Record<string, LiteralValue>>;
}

export interface ClientCall {
  readonly call: CallFence;
  readonly contributionKind: string;
  readonly contributionId: ContributionId;
  readonly input: LiteralValue;
}

export interface ResourceDescriptor {
  readonly path: string;
  readonly digest: string;
  /** Canonical non-negative signed-long decimal; never convert this value to Number. */
  readonly byteLength: string;
}

export interface Assignment {
  readonly pluginId: string;
  readonly facetId: string;
  readonly runtimeInstanceId: string;
  readonly executionTarget: string;
  readonly entryModule: string;
  readonly payloadDigest: string;
  readonly requiredCapabilities: readonly string[];
  readonly resources: readonly ResourceDescriptor[];
}

export interface Contribution {
  readonly contributionKind: string;
  readonly contributionId: ContributionId;
  readonly registrationIdentity: string;
}

export interface VerifiedResource {
  readonly bytes: Uint8Array;
  readonly byteLength: string;
}

export interface ClientResourceRequest {
  readonly session: SessionFence;
  readonly targetRevision: string;
  readonly runtimeInstanceId: string;
  readonly descriptor: ResourceDescriptor;
}

/** Internal transport boundary; it always receives the exact assignment fence. */
export interface ClientResourceProvider {
  load(request: ClientResourceRequest): Promise<Uint8Array>;
}

/** Adapter-owned verification boundary; core only consumes its completed result. */
export interface VerifiedResourceLoader {
  loadVerified(request: ClientResourceRequest): Promise<VerifiedResource>;
}

/** Plugin-facing view permanently bound to one assignment. */
export interface ClientResourceView {
  load(path: string): Promise<VerifiedResource>;
}

/** Plugin callers cannot provide a session, revision, or registration fence. */
export interface HostCaller {
  call(contributionKind: string, contributionId: ContributionId, input: LiteralValue): Promise<LiteralValue>;
}

export interface ClientContext {
  readonly scope: ClientScope;
  readonly host: HostCaller;
  readonly resources: ClientResourceView;
}

export interface ClientModule {
  prepare?(context: ClientContext): void | Promise<void>;
  activate?(context: ClientContext): void | Promise<void>;
  drain?(context: ClientContext): void | Promise<void>;
  stop?(context: ClientContext): void | Promise<void>;
}
