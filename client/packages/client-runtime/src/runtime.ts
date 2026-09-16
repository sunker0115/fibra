import type {
  Assignment,
  ClientCall,
  ClientContext,
  ClientModule,
  ClientResourceProvider,
  ClientResourceView,
  Contribution,
  ContributionId,
  HostCaller,
  LifecycleFence,
  LiteralValue,
  SessionFence,
  VerifiedResourceLoader,
} from "@sstlfsj/fibra-client-api";

import { VerifiedResourceCache } from "./resource-cache.js";
import { Scope } from "./scope.js";

export type LifecyclePhase = "prepare" | "activate" | "drain" | "stop";
export type ClientInstancePhase = "NEW" | "PREPARING" | "PREPARED" | "ACTIVATING" | "ACTIVE" | "DRAINING" | "DRAINED" | "STOPPING" | "STOPPED" | "FAILED";

export class ClientRuntimeError extends Error {
  constructor(readonly code: "STALE_OPERATION" | "ILLEGAL_LIFECYCLE_TRANSITION" | "UNAUTHORIZED_RUNTIME", message: string) {
    super(message);
    this.name = "ClientRuntimeError";
  }
}

export interface ClientSessionSnapshot {
  readonly session: SessionFence;
  readonly viewRevision: string;
  readonly targetRevision: string;
  readonly assignments: readonly Assignment[];
  readonly contributions: readonly Contribution[];
}

export interface ClientSessionRuntimeOptions {
  readonly session: SessionFence;
  readonly createModule: (assignment: Assignment, context: ClientContext) => ClientModule | Promise<ClientModule>;
  readonly hostCall?: (request: ClientCall) => Promise<LiteralValue>;
  readonly resourceProvider?: ClientResourceProvider;
  readonly verifiedResourceLoader?: VerifiedResourceLoader;
}

/** One exact session fence owns isolated instance actors and its verified resource cache. */
export class ClientSessionRuntime {
  private readonly actors = new Map<string, ClientInstanceActor>();
  private readonly cache = new VerifiedResourceCache();
  private detached = false;
  private detaching = false;
  private mutationTail: Promise<void> = Promise.resolve();
  private detachResult: Promise<void> | undefined;

  constructor(private readonly options: ClientSessionRuntimeOptions) {}

  get actorCount(): number {
    return this.actors.size;
  }

  get operationCount(): number {
    let count = 0;
    for (const actor of this.actors.values()) count += actor.operationCount;
    return count;
  }

  phaseOf(runtimeInstanceId: string): ClientInstancePhase | undefined {
    return this.actors.get(runtimeInstanceId)?.phase;
  }

  applySnapshot(snapshot: ClientSessionSnapshot): Promise<void> {
    this.assertSession(snapshot.session);
    if (this.detached || this.detaching) return Promise.reject(new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "session is detached"));
    return this.mutate(async () => {
      if (this.detached) throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "session is detached");
      this.validateSnapshot(snapshot);
      await this.commitSnapshot(snapshot);
    });
  }

  private async commitSnapshot(snapshot: ClientSessionSnapshot): Promise<void> {
    const next = new Map(snapshot.assignments.map((assignment) => [assignment.runtimeInstanceId, assignment]));
    if (next.size !== snapshot.assignments.length) throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "snapshot contains duplicate runtime assignments");

    for (const [id, actor] of this.actors) {
      const assignment = next.get(id);
      if (assignment !== undefined) {
        if (!sameAssignment(actor.assignment, assignment)) {
          throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "a retained runtime assignment changed");
        }
        actor.stageSnapshot(snapshot.targetRevision, snapshot.viewRevision, snapshot.contributions);
        next.delete(id);
      } else {
        actor.revoke();
      }
    }
    const retired = [...this.actors.entries()].filter(([, actor]) => !actor.authorized);
    await Promise.all(retired.map(async ([id, actor]) => {
      await actor.retire();
      this.actors.delete(id);
    }));
    for (const assignment of next.values()) {
      this.actors.set(assignment.runtimeInstanceId, new ClientInstanceActor(assignment, snapshot, this.options, this.cache));
    }
  }

  execute(phase: LifecyclePhase, fence: LifecycleFence): Promise<void> {
    if (!sameSession(this.options.session, fence.session)) {
      return Promise.reject(new ClientRuntimeError("STALE_OPERATION", "command session does not match this runtime session"));
    }
    if (this.detached) return Promise.reject(new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "session is detached"));
    const actor = this.actors.get(fence.runtimeInstanceId);
    if (actor === undefined || !actor.authorized) {
      return Promise.reject(new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "runtime is not assigned by the current snapshot"));
    }
    return actor.execute(phase, fence);
  }

  detach(session: SessionFence): Promise<void> {
    this.assertSession(session);
    if (this.detachResult !== undefined) return this.detachResult;
    this.detaching = true;
    this.detachResult = this.mutate(async () => {
      this.detached = true;
      for (const actor of this.actors.values()) actor.revoke();
      const retired = [...this.actors.entries()];
      const results = await Promise.allSettled(retired.map(async ([id, actor]) => {
        try { await actor.retire(); } finally { this.actors.delete(id); }
      }));
      this.cache.clear();
      const failures = results.filter((result): result is PromiseRejectedResult => result.status === "rejected").map((result) => result.reason);
      if (failures.length === 1) throw failures[0];
      if (failures.length > 1) throw new AggregateError(failures, "session detach failed");
    });
    return this.detachResult;
  }

  private mutate(work: () => Promise<void>): Promise<void> {
    const result = this.mutationTail.then(work, work);
    this.mutationTail = result.catch(() => undefined);
    return result;
  }

  private validateSnapshot(snapshot: ClientSessionSnapshot): void {
    const ids = new Set<string>();
    for (const assignment of snapshot.assignments) {
      if (ids.has(assignment.runtimeInstanceId) || !validAssignment(assignment)) {
        throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "snapshot contains an invalid assignment");
      }
      ids.add(assignment.runtimeInstanceId);
      const actor = this.actors.get(assignment.runtimeInstanceId);
      if (actor !== undefined && !sameAssignment(actor.assignment, assignment)) {
        throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "a retained runtime assignment changed");
      }
    }
  }

  private assertSession(session: SessionFence): void {
    if (!sameSession(this.options.session, session)) {
      throw new ClientRuntimeError("STALE_OPERATION", "command session does not match this runtime session");
    }
  }
}

class ClientInstanceActor {
  readonly scope = new Scope();
  readonly assignment: Assignment;
  private readonly operations = new Map<string, Operation>();
  private module: ClientModule | undefined;
  private stopResult: Promise<void> | undefined;
  private retireResult: Promise<void> | undefined;
  private stopAccepted = false;
  private stopStarted = false;
  private tail: Promise<void> = Promise.resolve();
  private accepted: "NEW" | "PREPARING" | "ACTIVATING" | "DRAINING" | "STOPPING" = "NEW";
  private _phase: ClientInstancePhase = "NEW";
  private _authorized = true;
  private targetRevision: string;
  private committedPhaseRevision: string | undefined;
  private viewRevision: string;
  private contributions: readonly Contribution[];

  constructor(assignment: Assignment, snapshot: ClientSessionSnapshot, private readonly options: ClientSessionRuntimeOptions, private readonly cache: VerifiedResourceCache) {
    this.assignment = assignment;
    this.targetRevision = snapshot.targetRevision;
    this.viewRevision = snapshot.viewRevision;
    this.contributions = snapshot.contributions;
  }

  get phase(): ClientInstancePhase {
    return this._phase;
  }

  get authorized(): boolean {
    return this._authorized;
  }

  get operationCount(): number {
    return this.operations.size;
  }

  stageSnapshot(targetRevision: string, viewRevision: string, contributions: readonly Contribution[]): void {
    if (!this._authorized) throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "retired runtime cannot be reauthorized");
    this.targetRevision = targetRevision;
    this.viewRevision = viewRevision;
    this.contributions = contributions;
  }

  revoke(): void {
    this._authorized = false;
  }

  execute(phase: LifecyclePhase, fence: LifecycleFence): Promise<void> {
    const existing = this.operations.get(fence.lifecycleOperationId);
    if (existing !== undefined) {
      if (existing.phase === phase && sameLifecycle(existing.fence, fence)) return existing.promise;
      return Promise.reject(new ClientRuntimeError("STALE_OPERATION", "lifecycle operation id was replayed with different contents"));
    }
    if (!this._authorized || fence.targetRevision !== this.targetRevision) {
      return Promise.reject(new ClientRuntimeError("STALE_OPERATION", "lifecycle fence is stale or unauthorized"));
    }
    if (!canAccept(this.accepted, this._phase, phase, this.stopAccepted)) {
      return Promise.reject(new ClientRuntimeError("ILLEGAL_LIFECYCLE_TRANSITION", `cannot ${phase} from ${this._phase}`));
    }
    if (phase === "stop") this.stopAccepted = true;
    this.accepted = acceptedPhase(phase);
    const promise = this.enqueue(() => this.apply(phase, fence));
    this.operations.set(fence.lifecycleOperationId, { phase, fence, promise });
    return promise;
  }

  retire(): Promise<void> {
    if (this.retireResult !== undefined) return this.retireResult;
    this.retireResult = this.enqueue(async () => {
      const failures: unknown[] = [];
      if (this.stopResult !== undefined) {
        await this.stopResult;
        this.operations.clear();
        return;
      } else if (this.module?.stop !== undefined && this._phase !== "STOPPED") {
        try {
          await this.module.stop(this.context());
        } catch (failure) {
          failures.push(failure);
        }
      }
      try {
        await this.scope.close();
      } catch (failure) {
        failures.push(failure);
      }
      this.operations.clear();
      if (failures.length === 1) throw failures[0];
      if (failures.length > 1) throw new AggregateError(failures, "runtime retirement failed");
    });
    return this.retireResult;
  }

  private enqueue(work: () => Promise<void>): Promise<void> {
    const result = this.tail.then(work, work);
    this.tail = result.catch(() => undefined);
    return result;
  }

  private async apply(phase: LifecyclePhase, fence: LifecycleFence): Promise<void> {
    if (!this._authorized) {
      if (phase !== "stop") this._phase = "FAILED";
      else if (!this.stopStarted) this.releaseStopReservation();
      throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "runtime assignment was revoked");
    }
    if (phase !== "stop" && this._phase === "FAILED") {
      throw new ClientRuntimeError("ILLEGAL_LIFECYCLE_TRANSITION", "failed runtime cannot continue lifecycle work");
    }
    if (fence.targetRevision !== this.targetRevision) {
      if (phase !== "stop") this._phase = "FAILED";
      else if (!this.stopStarted) this.releaseStopReservation();
      throw new ClientRuntimeError("STALE_OPERATION", "lifecycle fence target revision is stale");
    }
    if (phase === "prepare") {
      this._phase = "PREPARING";
      try {
        this.module = await this.options.createModule(this.assignment, this.context());
        this.assertCurrent(fence);
        await this.module.prepare?.(this.context());
        this.assertCurrent(fence);
        this._phase = "PREPARED";
        this.committedPhaseRevision = fence.targetRevision;
      } catch (failure) {
        this._phase = "FAILED";
        throw failure;
      }
      return;
    }
    if (phase === "stop") {
      this.stopStarted = true;
      const failed = this._phase === "FAILED";
      if (!failed) this._phase = "STOPPING";
      const failures: unknown[] = [];
      this.stopResult = (async () => {
        try { await this.module?.stop?.(this.context()); } catch (failure) { failures.push(failure); }
        try { await this.scope.close(); } catch (failure) { failures.push(failure); }
        if (failures.length === 1) throw failures[0];
        if (failures.length > 1) throw new AggregateError(failures, "runtime stop failed");
      })();
      try {
        await this.stopResult;
        this.assertCurrent(fence);
      } catch (failure) {
        this._phase = "FAILED";
        throw failure;
      }
      if (!failed && failures.length === 0) this._phase = "STOPPED";
      else this._phase = "FAILED";
      return;
    }
    const transitional = phase === "activate" ? "ACTIVATING" : "DRAINING";
    const committed = phase === "activate" ? "ACTIVE" : "DRAINED";
    this._phase = transitional;
    try {
      await this.module?.[phase]?.(this.context());
      this.assertCurrent(fence);
      this._phase = committed;
      this.committedPhaseRevision = fence.targetRevision;
    } catch (failure) {
      this._phase = "FAILED";
      throw failure;
    }
  }

  private context(): ClientContext {
    const resources = new Map(this.assignment.resources.map((resource) => [resource.path, resource]));
    const view: ClientResourceView = {
      load: (path) => {
        try { this.assertUsable(); } catch (failure) { return Promise.reject(failure); }
        const descriptor = resources.get(path);
        if (descriptor === undefined) return Promise.reject(new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "resource is outside this assignment"));
        if (this.options.verifiedResourceLoader === undefined) return Promise.reject(new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "verified resource loader is unavailable"));
        const request = {
          session: this.options.session,
          targetRevision: this.targetRevision,
          runtimeInstanceId: this.assignment.runtimeInstanceId,
          descriptor,
        };
        return this.cache.loadVerified(descriptor, async () => {
          const result = await this.options.verifiedResourceLoader!.loadVerified(request);
          return result;
        }).then((result) => { this.assertUsable(); return result; });
      },
    };
    const host: HostCaller = {
      call: (contributionKind: string, contributionId: ContributionId, input: LiteralValue) => {
        try { this.assertUsable(); } catch (failure) { return Promise.reject(failure); }
        const contribution = this.contributions.find((candidate) => candidate.contributionKind === contributionKind
          && sameContributionId(candidate.contributionId, contributionId));
        if (contribution === undefined || this.options.hostCall === undefined) {
          return Promise.reject(new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "contribution is not available to this snapshot"));
        }
        return this.options.hostCall({
          call: { session: this.options.session, expectedViewRevision: this.viewRevision, registrationIdentity: contribution.registrationIdentity },
          contributionKind,
          contributionId,
          input,
        }).then((value) => { this.assertUsable(); return value; });
      },
    };
    return { scope: this.scope.view, host, resources: view };
  }

  private assertUsable(): void {
    if (!this._authorized || this._phase === "STOPPED" || this.scope.closed) {
      throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "runtime context is retired");
    }
  }

  private assertCurrent(fence: LifecycleFence): void {
    if (!this._authorized) throw new ClientRuntimeError("UNAUTHORIZED_RUNTIME", "runtime assignment was revoked during handler execution");
    if (this.targetRevision !== fence.targetRevision) throw new ClientRuntimeError("STALE_OPERATION", "lifecycle authorization changed during handler execution");
  }

  private releaseStopReservation(): void {
    this.stopAccepted = false;
    this.accepted = this._phase === "DRAINED" ? "DRAINING" : "NEW";
  }
}

interface Operation {
  readonly phase: LifecyclePhase;
  readonly fence: LifecycleFence;
  readonly promise: Promise<void>;
}

function canAccept(accepted: ClientInstanceActor["accepted"], phase: ClientInstancePhase, requested: LifecyclePhase, stopAccepted: boolean): boolean {
  if (requested === "stop") return !stopAccepted && (accepted === "DRAINING" || phase === "FAILED");
  if (phase === "FAILED" || phase === "STOPPED") return false;
  return (accepted === "NEW" && requested === "prepare")
    || (accepted === "PREPARING" && requested === "activate")
    || (accepted === "ACTIVATING" && requested === "drain");
}

function acceptedPhase(phase: LifecyclePhase): ClientInstanceActor["accepted"] {
  return phase === "prepare" ? "PREPARING" : phase === "activate" ? "ACTIVATING" : phase === "drain" ? "DRAINING" : "STOPPING";
}

function sameSession(left: SessionFence, right: SessionFence): boolean {
  return left.hostInstanceId === right.hostInstanceId && left.clientExecutionId === right.clientExecutionId;
}

function sameLifecycle(left: LifecycleFence, right: LifecycleFence): boolean {
  return sameSession(left.session, right.session)
    && left.targetRevision === right.targetRevision
    && left.runtimeInstanceId === right.runtimeInstanceId
    && left.lifecycleOperationId === right.lifecycleOperationId;
}

function sameContributionId(left: ContributionId, right: ContributionId): boolean {
  return left.providerInstanceId === right.providerInstanceId && left.localName === right.localName;
}

function sameAssignment(left: Assignment, right: Assignment): boolean {
  return left.pluginId === right.pluginId && left.facetId === right.facetId
    && left.runtimeInstanceId === right.runtimeInstanceId && left.executionTarget === right.executionTarget
    && left.entryModule === right.entryModule && left.payloadDigest === right.payloadDigest
    && sameStrings(left.requiredCapabilities, right.requiredCapabilities)
    && left.resources.length === right.resources.length
    && left.resources.every((resource, index) => resource.path === right.resources[index]?.path
      && resource.digest === right.resources[index]?.digest && resource.byteLength === right.resources[index]?.byteLength);
}

function sameStrings(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function validAssignment(assignment: Assignment): boolean {
  const paths = new Set<string>();
  for (const resource of assignment.resources) {
    if (!/^(0|[1-9][0-9]*)$/.test(resource.byteLength) || resource.byteLength.length > 19
      || (resource.byteLength.length === 19 && resource.byteLength > "9223372036854775807")
      || !normalizedResourcePath(resource.path) || paths.has(resource.path)) return false;
    paths.add(resource.path);
  }
  return paths.has(assignment.entryModule);
}

function normalizedResourcePath(path: string): boolean {
  return path.length > 0 && !path.startsWith("/") && !path.endsWith("/") && !path.includes("\\")
    && !/^[A-Za-z][A-Za-z0-9+.-]*:/.test(path)
    && path.split("/").every((segment) => segment.length > 0 && segment !== "." && segment !== "..");
}
