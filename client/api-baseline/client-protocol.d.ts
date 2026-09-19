import type { Assignment, CallFence, ClientCall, ClientError, Contribution, ContributionId, LifecycleFence, LiteralValue, SessionFence } from "@sstlfsj/fibra-client-api";
export type { Assignment, CallFence, ClientCall, Contribution, ContributionId, LifecycleFence, LiteralValue, SessionFence } from "@sstlfsj/fibra-client-api";
export type LifecycleOutcome = {
    readonly kind: "APPLIED";
} | {
    readonly kind: "FAILED";
    readonly failure: ClientError;
};
export type CallOutcome = {
    readonly kind: "SUCCESS";
    readonly value: LiteralValue;
} | {
    readonly kind: "FAILED";
    readonly failure: ClientError;
};
export interface ExecutionObservation {
    readonly unitTargetRevision: string;
    readonly runtimeInstanceId: string;
    readonly lifecycleOperationId: string;
    readonly state: "PENDING" | "ACTIVE" | "FAILED";
    readonly failure?: ClientError;
}
export interface Envelope<Type extends string, Payload> {
    readonly protocolVersion: 1;
    readonly messageId: string;
    readonly type: Type;
    readonly payload: Payload;
}
export type ProtocolEnvelope = Envelope<"client.hello", {
    readonly identity: {
        readonly clientNonce: string;
    };
    readonly executionTarget: string;
    readonly capabilities: readonly string[];
}> | Envelope<"host.welcome" | "client.detach", {
    readonly session: SessionFence;
}> | Envelope<"host.snapshot", {
    readonly session: SessionFence;
    readonly viewRevision: string;
    readonly targetRevision: string;
    readonly targetDigest: string;
    readonly assignments: readonly Assignment[];
    readonly contributions: readonly Contribution[];
}> | Envelope<"host.prepare" | "host.activate" | "host.drain" | "host.stop", {
    readonly lifecycle: LifecycleFence;
}> | Envelope<"client.lifecycle-result", {
    readonly lifecycle: LifecycleFence;
    readonly outcome: LifecycleOutcome;
}> | Envelope<"client.observed", {
    readonly session: SessionFence;
    readonly executions: readonly ExecutionObservation[];
}> | Envelope<"client.call", ClientCall> | Envelope<"host.call-result", {
    readonly call: CallFence;
    readonly contributionKind: string;
    readonly contributionId: ContributionId;
    readonly outcome: CallOutcome;
}>;
export declare const PROTOCOL_VERSION = 1;
export declare const MAX_ENVELOPE_BYTES: number;
export declare const MAX_NESTING_DEPTH = 64;
export declare const MAX_DECIMAL_CHARACTERS = 1000;
type ErrorCode = "MALFORMED_MESSAGE" | "INVALID_IDENTITY" | "UNSUPPORTED_PROTOCOL";
export declare class ClientProtocolError extends Error {
    readonly code: ErrorCode;
    constructor(code: ErrorCode, message: string);
}
export declare function decodeEnvelope(wire: string): ProtocolEnvelope;
export declare function decodeFixtures(wire: string): readonly ProtocolEnvelope[];
export declare function encodeEnvelope(envelope: ProtocolEnvelope): string;
