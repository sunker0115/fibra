import type { LifecycleFence } from "@sstlfsj/fibra-client-api";

export type LifecyclePhase = "prepare" | "activate" | "drain" | "stop";

export interface ClientLifecycleHandlers {
  readonly prepare?: (fence: LifecycleFence) => void | Promise<void>;
  readonly activate?: (fence: LifecycleFence) => void | Promise<void>;
  readonly drain?: (fence: LifecycleFence) => void | Promise<void>;
  readonly stop?: (fence: LifecycleFence) => void | Promise<void>;
}

export class ClientRuntimeError extends Error {
  constructor(readonly code: "STALE_OPERATION", message: string) {
    super(message);
    this.name = "ClientRuntimeError";
  }
}

/** Serializes client lifecycle work and keeps acknowledgement fences exact. */
export class ClientLifecycleRuntime {
  private queue: Promise<void> = Promise.resolve();
  private readonly commands = new Map<string, Promise<void>>();
  private readonly pending = new Map<string, LifecycleFence>();

  constructor(private readonly handlers: ClientLifecycleHandlers) {}

  execute(phase: LifecyclePhase, fence: LifecycleFence): Promise<void> {
    const key = `${phase}\u0000${fenceKey(fence)}`;
    const existing = this.commands.get(key);
    if (existing !== undefined) return existing;
    const handler = this.handlers[phase];
    const command = this.queue.then(() => handler?.(fence));
    this.queue = command.catch(() => undefined);
    this.commands.set(key, command);
    return command;
  }

  begin(fence: LifecycleFence): void {
    this.pending.set(runtimeKey(fence), fence);
  }

  accept(fence: LifecycleFence): void {
    const key = runtimeKey(fence);
    const current = this.pending.get(key);
    if (current === undefined || fenceKey(current) !== fenceKey(fence)) {
      throw new ClientRuntimeError("STALE_OPERATION", "lifecycle response does not match the current operation");
    }
    this.pending.delete(key);
  }
}

function runtimeKey(fence: LifecycleFence): string {
  return `${fence.session.hostInstanceId}\u0000${fence.session.clientExecutionId}\u0000${fence.runtimeInstanceId}`;
}

function fenceKey(fence: LifecycleFence): string {
  return `${runtimeKey(fence)}\u0000${fence.targetRevision}\u0000${fence.lifecycleOperationId}`;
}
