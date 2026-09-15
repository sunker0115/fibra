/** Framework-neutral contracts shared by client implementations. */

export interface ClientDisposable {
  dispose(): void | Promise<void>;
}

export interface ClientScope extends ClientDisposable {
  readonly closed: boolean;
  close(): Promise<void>;
  child(): ClientScope;
  effect(cleanup: ClientDisposable | (() => void | Promise<void>)): ClientDisposable;
  listen(register: () => ClientDisposable | (() => void | Promise<void>)): ClientDisposable;
  timer(register: () => ClientDisposable | (() => void | Promise<void>)): ClientDisposable;
}

export interface ClientError {
  readonly code: "MALFORMED_MESSAGE" | "INVALID_IDENTITY" | "UNSUPPORTED_PROTOCOL" | "STALE_OPERATION" | string;
  readonly message: string;
  readonly diagnostics: Readonly<Record<string, string>>;
}

type LiteralValue =
  | null
  | boolean
  | string
  | readonly LiteralValue[]
  | LiteralNumber
  | LiteralObject;

interface LiteralNumber {
  readonly kind: "NUMBER";
  readonly value: string;
}

interface LiteralObject {
  readonly kind: "OBJECT";
  readonly values: Readonly<Record<string, LiteralValue>>;
}

interface SessionFence {
  readonly hostInstanceId: string;
  readonly clientExecutionId: string;
}

interface CallFence {
  readonly session: SessionFence;
  readonly expectedViewRevision: string;
  readonly registrationIdentity: string;
}

interface ContributionId {
  readonly providerInstanceId: string;
  readonly localName: string;
}

interface ClientCall {
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
