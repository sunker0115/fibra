import type { ClientContext } from "@sstlfsj/fibra-client-api";

window.probe.evaluations.push("dom");
window.probe.events.push("dom:import-start");
await window.probe.waitImport();
export function activate(context: ClientContext) {
  window.probe.events.push("dom:activate");
  context.scope.listen(() => window.probe.registerMount((container) => {
    const element = document.createElement("p");
    element.dataset.testid = "dom-probe"; element.textContent = "DOM probe";
    container.append(element);
    return { dispose: () => element.remove() };
  }));
  context.scope.listen(() => {
    const listener = () => { window.probe.listenerCalls += 1; };
    window.addEventListener("probe-ping", listener);
    return () => window.removeEventListener("probe-ping", listener);
  });
  context.scope.timer(() => {
    const timer = window.setInterval(() => { window.probe.timerCalls += 1; }, 20);
    return () => window.clearInterval(timer);
  });
  return () => {
    window.probe.events.push("dom:return-dispose");
    if (window.probe.failure?.renderer === "dom" && window.probe.failure.failure === "effect") throw new Error("DOM effect cleanup failed");
  };
}
export function prepare() { window.probe.events.push("dom:prepare"); }
export function drain() { window.probe.events.push("dom:drain"); }
export function stop() { window.probe.events.push("dom:stop"); }
export async function dispose() {
  window.probe.events.push("dom:dispose-start");
  await window.probe.waitDisposal();
  if (window.probe.failure?.renderer === "dom" && window.probe.failure.failure === "module") throw new Error("DOM module cleanup failed");
  window.probe.events.push("dom:dispose-end");
}
