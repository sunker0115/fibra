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
    readonly runtimeInstanceId: string;
    /** Canonical positive signed-long decimal for the unit's creation revision; retained units keep it unchanged. */
    readonly unitTargetRevision: string;
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
    readonly desiredEntryId: string;
    readonly definitionId: string;
    readonly runtimeInstanceId: string;
    /** Canonical positive signed-long decimal for the unit's creation revision; retained units keep it unchanged. */
    readonly unitTargetRevision: string;
    readonly executionTarget: string;
    readonly config: LiteralValue;
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
/** Plugin callers cannot provide a session, revision, or registration fence. */
export interface HostCaller {
    call(contributionKind: string, contributionId: ContributionId, input: LiteralValue): Promise<LiteralValue>;
}
/** Capabilities and resolved assignment facts permanently bound to one client instance. */
export interface ClientInstanceContext {
    readonly desiredEntryId: string;
    readonly definitionId: string;
    /** Canonical positive signed-long decimal for the unit's creation revision. */
    readonly unitTargetRevision: string;
    readonly runtimeInstanceId: string;
    /** The resolved canonical config carried by the assignment. */
    readonly config: LiteralValue;
    readonly scope: ClientScope;
    readonly host: HostCaller;
}
export interface ClientModule {
    prepare?(): void | Promise<void>;
    activate?(): void | Promise<void>;
    drain?(): void | Promise<void>;
    stop?(): void | Promise<void>;
}
/** One definition can create independent modules for multiple desired entries. */
export interface ClientModuleDefinition {
    readonly definitionId: string;
    create(context: ClientInstanceContext): ClientModule | Promise<ClientModule>;
}
/** Shape exported by an assignment's loaded entry module. */
export interface ClientEntryModule {
    readonly definitions: readonly ClientModuleDefinition[];
}
