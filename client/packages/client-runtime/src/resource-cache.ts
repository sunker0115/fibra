import type { ResourceDescriptor, VerifiedResource } from "@sstlfsj/fibra-client-api";

/** Session-owned verified payload cache. It deliberately has no transport behavior. */
export class VerifiedResourceCache {
  private readonly values = new Map<string, Promise<VerifiedResource>>();

  loadVerified(descriptor: ResourceDescriptor, loader: () => Promise<VerifiedResource>): Promise<VerifiedResource> {
    const existing = this.values.get(descriptor.digest);
    const result = existing ?? this.start(descriptor, loader);
    return result.then((value) => {
      this.verify(descriptor, value);
      return value;
    });
  }

  clear(): void {
    this.values.clear();
  }

  private start(descriptor: ResourceDescriptor, loader: () => Promise<VerifiedResource>): Promise<VerifiedResource> {
    const pending = Promise.resolve().then(loader).then((value) => {
      this.verify(descriptor, value);
      return value;
    });
    this.values.set(descriptor.digest, pending);
    void pending.catch(() => {
      if (this.values.get(descriptor.digest) === pending) this.values.delete(descriptor.digest);
    });
    return pending;
  }

  private verify(descriptor: ResourceDescriptor, value: VerifiedResource): void {
    if (value.byteLength !== descriptor.byteLength) {
      throw new Error("verified resource byteLength does not match its descriptor");
    }
  }
}
