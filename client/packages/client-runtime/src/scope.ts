import type { ClientDisposable, ClientScope } from "@sstlfsj/fibra-client-api";

export class ClientScopeClosedError extends Error {
  constructor() {
    super("client scope is closing or closed");
    this.name = "ClientScopeClosedError";
  }
}

type Cleanup = () => void | Promise<void>;

class Effect implements ClientDisposable {
  private disposed = false;

  constructor(private readonly cleanup: Cleanup, private readonly remove: () => void) {}

  dispose(): void | Promise<void> {
    if (this.disposed) return;
    this.disposed = true;
    this.remove();
    return this.cleanup();
  }
}

/** Hierarchical owner for framework-neutral client resources. */
export class Scope implements ClientScope {
  private readonly children: Scope[] = [];
  private readonly effects: Effect[] = [];
  private closeResult: Promise<void> | undefined;

  get closed(): boolean {
    return this.closeResult !== undefined;
  }

  child(): Scope {
    this.assertOpen();
    const child = new Scope(() => this.removeChild(child));
    this.children.push(child);
    return child;
  }

  effect(cleanup: Cleanup | ClientDisposable): ClientDisposable {
    this.assertOpen();
    let owned: Effect;
    owned = new Effect(
      typeof cleanup === "function" ? cleanup : () => cleanup.dispose(),
      () => this.removeEffect(owned),
    );
    this.effects.push(owned);
    return owned;
  }

  /** Registers a listener cleanup under this scope. */
  listen(register: () => Cleanup | ClientDisposable): ClientDisposable {
    return this.effect(register());
  }

  /** Registers a timer cleanup under this scope. */
  timer(register: () => Cleanup | ClientDisposable): ClientDisposable {
    return this.effect(register());
  }

  close(): Promise<void> {
    if (this.closeResult === undefined) {
      this.closeResult = this.closeOwned();
    }
    return this.closeResult;
  }

  dispose(): Promise<void> {
    return this.close();
  }

  private async closeOwned(): Promise<void> {
    const failures: unknown[] = [];
    for (const child of [...this.children].reverse()) {
      await this.capture(() => child.close(), failures);
    }
    for (const effect of [...this.effects].reverse()) {
      await this.capture(() => effect.dispose(), failures);
    }
    this.parentRemove?.();
    if (failures.length > 0) {
      throw new AggregateError(failures, "client scope cleanup failed");
    }
  }

  constructor(private readonly parentRemove?: () => void) {}

  private async capture(cleanup: Cleanup, failures: unknown[]): Promise<void> {
    try {
      await cleanup();
    } catch (failure) {
      failures.push(failure);
    }
  }

  private assertOpen(): void {
    if (this.closeResult !== undefined) throw new ClientScopeClosedError();
  }

  private removeChild(child: Scope): void {
    const index = this.children.indexOf(child);
    if (index >= 0) this.children.splice(index, 1);
  }

  private removeEffect(effect: Effect): void {
    const index = this.effects.indexOf(effect);
    if (index >= 0) this.effects.splice(index, 1);
  }
}
