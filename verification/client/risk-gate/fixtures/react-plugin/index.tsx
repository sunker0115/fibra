import { createElement } from "react";
import { registerReactMount } from "@sstlfsj/fibra-client-react";
import type { ClientContext } from "@sstlfsj/fibra-client-api";

window.probe.evaluations.push("react");
export function activate(context: ClientContext) {
  window.probe.events.push("react:activate");
  registerReactMount(context, window.probe.registerMount, () => createElement("p", { "data-testid": "react-probe" }, "React probe"));
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
    window.probe.events.push("react:return-dispose");
    if (window.probe.failure?.renderer === "react" && window.probe.failure.failure === "effect") throw new Error("React effect cleanup failed");
  };
}
export function prepare() { window.probe.events.push("react:prepare"); }
export function drain() { window.probe.events.push("react:drain"); }
export function stop() { window.probe.events.push("react:stop"); }
export function dispose() {
  window.probe.events.push("react:dispose");
  if (window.probe.failure?.renderer === "react" && window.probe.failure.failure === "module") throw new Error("React module cleanup failed");
}
