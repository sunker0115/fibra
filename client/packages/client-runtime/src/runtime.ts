import type { LifecycleFence } from "./protocol.js";

export type LifecyclePhase = "prepare" | "activate" | "drain" | "stop";

export interface ClientLifecycleHandlers {
  readonly prepare?: (fence: LifecycleFence) => void | Promise<void>;
  readonly activate?: (fence: LifecycleFence) => void | Promise<void>;
  readonly drain?: (fence: LifecycleFence) => void | Promise<void>;
  readonly stop?: (fence: LifecycleFence) => void | Promise<void>;
}

export class ClientRuntimeError extends Error {
  constructor(readonly code: "STALE_OPERATION" | "ILLEGAL_LIFECYCLE_TRANSITION", message: string) {
    super(message);
    this.name = "ClientRuntimeError";
  }
}

/** Serializes client lifecycle work and keeps acknowledgement fences exact. */
export class ClientLifecycleRuntime {
  private readonly hosts = new Map<string, Map<string, Map<string, RuntimeState>>>();

  constructor(private readonly handlers: ClientLifecycleHandlers) {}

  execute(phase: LifecyclePhase, fence: LifecycleFence): Promise<void> {
    const state = this.state(fence);
    const existing = state.commands.find((command) => command.phase === phase && sameFence(command.fence, fence));
    if (existing !== undefined) return existing.promise;
    if (!canSchedule(state, phase, fence)) {
      return Promise.reject(new ClientRuntimeError("ILLEGAL_LIFECYCLE_TRANSITION", `cannot ${phase} after ${state.scheduled ?? "initial"}`));
    }
    state.scheduled = phase;
    if (phase === "prepare") state.targetRevision = fence.targetRevision;
    this.begin(fence);
    const handler = this.handlers[phase];
    const command = state.queue.then(() => {
      if (!canApply(state, phase, fence)) {
        throw new ClientRuntimeError("ILLEGAL_LIFECYCLE_TRANSITION", `cannot apply ${phase}`);
      }
      return handler?.(fence);
    }).then(() => {
      state.applied = phase;
    }, (failure) => {
      state.scheduled = state.applied;
      if (state.applied === undefined || state.applied === "stop") state.targetRevision = undefined;
      throw failure;
    });
    state.queue = command.catch(() => undefined);
    state.commands.push({ phase, fence, promise: command });
    return command;
  }

  begin(fence: LifecycleFence): void {
    this.state(fence).pending = fence;
  }

  accept(fence: LifecycleFence): void {
    const state = this.findState(fence);
    if (state === undefined || state.pending === undefined || !sameFence(state.pending, fence)) {
      throw new ClientRuntimeError("STALE_OPERATION", "lifecycle response does not match the current operation");
    }
    state.pending = undefined;
  }

  private state(fence: LifecycleFence): RuntimeState {
    let clients = this.hosts.get(fence.session.hostInstanceId);
    if (clients === undefined) this.hosts.set(fence.session.hostInstanceId, clients = new Map());
    let runtimes = clients.get(fence.session.clientExecutionId);
    if (runtimes === undefined) clients.set(fence.session.clientExecutionId, runtimes = new Map());
    let state = runtimes.get(fence.runtimeInstanceId);
    if (state === undefined) runtimes.set(fence.runtimeInstanceId, state = { queue: Promise.resolve(), commands: [] });
    return state;
  }

  private findState(fence: LifecycleFence): RuntimeState | undefined {
    return this.hosts.get(fence.session.hostInstanceId)?.get(fence.session.clientExecutionId)?.get(fence.runtimeInstanceId);
  }
}

interface RuntimeState {
  queue: Promise<void>;
  scheduled?: LifecyclePhase;
  applied?: LifecyclePhase;
  targetRevision?: string;
  pending?: LifecycleFence;
  commands: Array<{ phase: LifecyclePhase; fence: LifecycleFence; promise: Promise<void> }>;
}

function canSchedule(state: RuntimeState, phase: LifecyclePhase, fence: LifecycleFence): boolean {
  if (phase === "prepare") {
    return state.scheduled === undefined || (state.scheduled === "stop" && state.applied === "stop");
  }
  if (state.targetRevision !== fence.targetRevision) return false;
  switch (state.scheduled) {
    case "prepare": return phase === "activate";
    case "activate": return phase === "drain";
    case "drain": return phase === "stop";
    default: return false;
  }
}

function canApply(state: RuntimeState, phase: LifecyclePhase, fence: LifecycleFence): boolean {
  if (phase === "prepare") return state.applied === undefined || state.applied === "stop";
  if (state.targetRevision !== fence.targetRevision) return false;
  switch (state.applied) {
    case "prepare": return phase === "activate";
    case "activate": return phase === "drain";
    case "drain": return phase === "stop";
    default: return false;
  }
}

function sameFence(left: LifecycleFence, right: LifecycleFence): boolean {
  return left.session.hostInstanceId === right.session.hostInstanceId
    && left.session.clientExecutionId === right.session.clientExecutionId
    && left.targetRevision === right.targetRevision
    && left.runtimeInstanceId === right.runtimeInstanceId
    && left.lifecycleOperationId === right.lifecycleOperationId;
}
