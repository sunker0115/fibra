import type { ReactNode } from "react";
import { createRoot } from "react-dom/client";
import type { ClientContext, ClientDisposable } from "@sstlfsj/fibra-client-api";

export type ReactMountFactory = (container: Element) => ClientDisposable;

/** Renderer-only adapter. The host supplies registration; the instance scope owns roots and withdrawal. */
export function registerReactMount(
  context: ClientContext,
  register: (factory: ReactMountFactory) => ClientDisposable,
  render: () => ReactNode,
): ClientDisposable {
  return context.scope.listen(() => register((container) => {
    if (context.scope.closed) throw new Error("client scope is closed");
    const root = createRoot(container);
    const ownedRoot = context.scope.effect(() => root.unmount());
    root.render(render());
    return ownedRoot;
  }));
}
