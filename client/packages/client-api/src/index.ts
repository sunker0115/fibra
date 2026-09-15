/** Framework-neutral contracts shared by client implementations. */

export interface ClientDisposable {
  dispose(): void | Promise<void>;
}

export interface ClientScope extends ClientDisposable {
  readonly closed: boolean;
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
  /** An opaque, canonical positive signed-long decimal string. */
  readonly targetRevision: string;
  readonly runtimeInstanceId: string;
  readonly lifecycleOperationId: string;
}

export interface CallFence {
  readonly session: SessionFence;
  readonly expectedViewRevision: string;
  /** An opaque, canonical positive signed-long decimal string. */
  readonly registrationIdentity: string;
}

export interface ContributionId {
  readonly providerInstanceId: string;
  readonly localName: string;
}

export type LiteralValue =
  | null
  | boolean
  | string
  | readonly LiteralValue[]
  | LiteralNumber
  | LiteralObject;

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

export interface HostCaller {
  call(request: ClientCall): Promise<LiteralValue>;
}

export interface ClientContext {
  readonly scope: ClientScope;
  readonly host: HostCaller;
}

export interface ClientModule {
  prepare?(context: ClientContext): void | Promise<void>;
  activate?(context: ClientContext): void | Promise<void>;
  drain?(context: ClientContext): void | Promise<void>;
  stop?(context: ClientContext): void | Promise<void>;
}

export type ProtocolEnvelope =
  | HelloEnvelope
  | WelcomeEnvelope
  | SnapshotEnvelope
  | LifecycleCommandEnvelope
  | LifecycleResultEnvelope
  | ObservedEnvelope
  | CallEnvelope
  | CallResultEnvelope
  | DetachEnvelope;

export interface Envelope<Type extends string, Payload> {
  readonly protocolVersion: 1;
  readonly messageId: string;
  readonly type: Type;
  readonly payload: Payload;
}

export type HelloEnvelope = Envelope<"client.hello", {
  readonly identity: { readonly clientNonce: string };
  readonly executionTarget: string;
  readonly capabilities: readonly string[];
}>;
export type WelcomeEnvelope = Envelope<"host.welcome", { readonly session: SessionFence }>;
export type SnapshotEnvelope = Envelope<"host.snapshot", {
  readonly session: SessionFence;
  readonly viewRevision: string;
  readonly targetRevision: string;
  readonly targetDigest: string;
  readonly assignments: readonly Assignment[];
  readonly contributions: readonly Contribution[];
}>;
export type LifecycleCommandEnvelope = Envelope<"host.prepare" | "host.activate" | "host.drain" | "host.stop", {
  readonly lifecycle: LifecycleFence;
}>;
export type LifecycleResultEnvelope = Envelope<"client.lifecycle-result", {
  readonly lifecycle: LifecycleFence;
  readonly outcome: LifecycleOutcome;
}>;
export type ObservedEnvelope = Envelope<"client.observed", {
  readonly session: SessionFence;
  readonly executions: readonly ExecutionObservation[];
}>;
export type CallEnvelope = Envelope<"client.call", ClientCall>;
export type CallResultEnvelope = Envelope<"host.call-result", {
  readonly call: CallFence;
  readonly contributionKind: string;
  readonly contributionId: ContributionId;
  readonly outcome: CallOutcome;
}>;
export type DetachEnvelope = Envelope<"client.detach", { readonly session: SessionFence }>;

export interface Assignment {
  readonly pluginId: string;
  readonly facetId: string;
  readonly runtimeInstanceId: string;
  readonly executionTarget: string;
  readonly entryModule: string;
  readonly payloadDigest: string;
  readonly requiredCapabilities: readonly string[];
  readonly resources: readonly Resource[];
}

export interface Resource {
  readonly path: string;
  readonly digest: string;
  readonly content: { readonly kind: "URL"; readonly url: string } | { readonly kind: "BYTES"; readonly base64: string };
}

export interface Contribution {
  readonly contributionKind: string;
  readonly contributionId: ContributionId;
  readonly registrationIdentity: string;
}

export type LifecycleOutcome = { readonly kind: "APPLIED" } | { readonly kind: "FAILED"; readonly failure: ClientError };
export type CallOutcome = { readonly kind: "SUCCESS"; readonly value: LiteralValue } | { readonly kind: "FAILED"; readonly failure: ClientError };
export interface ExecutionObservation {
  readonly targetRevision: string;
  readonly runtimeInstanceId: string;
  readonly lifecycleOperationId: string;
  readonly state: "PENDING" | "ACTIVE" | "FAILED";
  readonly failure?: ClientError;
}
