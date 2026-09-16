import type { Assignment, CallFence, ClientCall, ClientError, Contribution, ContributionId, LifecycleFence, LiteralValue, SessionFence } from "@sstlfsj/fibra-client-api";

export type { Assignment, CallFence, ClientCall, Contribution, ContributionId, LifecycleFence, LiteralValue, SessionFence } from "@sstlfsj/fibra-client-api";
export type LifecycleOutcome = { readonly kind: "APPLIED" } | { readonly kind: "FAILED"; readonly failure: ClientError };
export type CallOutcome = { readonly kind: "SUCCESS"; readonly value: LiteralValue } | { readonly kind: "FAILED"; readonly failure: ClientError };
export interface ExecutionObservation { readonly targetRevision: string; readonly runtimeInstanceId: string; readonly lifecycleOperationId: string; readonly state: "PENDING" | "ACTIVE" | "FAILED"; readonly failure?: ClientError; }
export interface Envelope<Type extends string, Payload> { readonly protocolVersion: 1; readonly messageId: string; readonly type: Type; readonly payload: Payload; }
export type ProtocolEnvelope =
  | Envelope<"client.hello", { readonly identity: { readonly clientNonce: string }; readonly executionTarget: string; readonly capabilities: readonly string[] }>
  | Envelope<"host.welcome" | "client.detach", { readonly session: SessionFence }>
  | Envelope<"host.snapshot", { readonly session: SessionFence; readonly viewRevision: string; readonly targetRevision: string; readonly targetDigest: string; readonly assignments: readonly Assignment[]; readonly contributions: readonly Contribution[] }>
  | Envelope<"host.prepare" | "host.activate" | "host.drain" | "host.stop", { readonly lifecycle: LifecycleFence }>
  | Envelope<"client.lifecycle-result", { readonly lifecycle: LifecycleFence; readonly outcome: LifecycleOutcome }>
  | Envelope<"client.observed", { readonly session: SessionFence; readonly executions: readonly ExecutionObservation[] }>
  | Envelope<"client.call", ClientCall>
  | Envelope<"host.call-result", { readonly call: CallFence; readonly contributionKind: string; readonly contributionId: ContributionId; readonly outcome: CallOutcome }>;

export const PROTOCOL_VERSION = 1;
export const MAX_ENVELOPE_BYTES = 1024 * 1024;
export const MAX_NESTING_DEPTH = 64;
export const MAX_DECIMAL_CHARACTERS = 1000;

type JsonValue = null | boolean | number | JsonNumber | string | JsonValue[] | JsonObject;
type JsonObject = { readonly [key: string]: JsonValue };
type ErrorCode = "MALFORMED_MESSAGE" | "INVALID_IDENTITY" | "UNSUPPORTED_PROTOCOL";

export class ClientProtocolError extends Error {
  constructor(readonly code: ErrorCode, message: string) {
    super(message);
    this.name = "ClientProtocolError";
  }
}

export function decodeEnvelope(wire: string): ProtocolEnvelope {
  if (utf8ByteLength(wire) > MAX_ENVELOPE_BYTES) {
    throw malformed("envelope exceeds 1 MiB");
  }
  return validateEnvelope(new StrictJsonReader(wire).read());
}

export function decodeFixtures(wire: string): readonly ProtocolEnvelope[] {
  const root = new StrictJsonReader(wire).read();
  if (!Array.isArray(root)) throw malformed("fixtures must be an array");
  return root.map((item) => {
    if (utf8ByteLength(JSON.stringify(item)) > MAX_ENVELOPE_BYTES) {
      throw malformed("envelope exceeds 1 MiB");
    }
    return validateEnvelope(item);
  });
}

export function encodeEnvelope(envelope: ProtocolEnvelope): string {
  const result = JSON.stringify(validateEnvelope(envelope as unknown as JsonValue));
  if (utf8ByteLength(result) > MAX_ENVELOPE_BYTES) {
    throw malformed("envelope exceeds 1 MiB");
  }
  return result;
}

function validateEnvelope(value: JsonValue): ProtocolEnvelope {
  const envelope = object(value, "envelope");
  exact(envelope, ["protocolVersion", "messageId", "type", "payload"]);
  const version = protocolVersion(envelope.protocolVersion);
  if (version !== PROTOCOL_VERSION) {
    throw new ClientProtocolError("UNSUPPORTED_PROTOCOL", "protocolVersion must be 1");
  }
  const messageId = text(envelope, "messageId");
  const type = text(envelope, "type");
  const payload = object(envelope.payload, "payload");

  switch (type) {
    case "client.hello":
      phase(payload, "identity", ["identity", "executionTarget", "capabilities"]);
      exact(object(payload.identity, "identity", "INVALID_IDENTITY"), ["clientNonce"], "INVALID_IDENTITY");
      text(object(payload.identity, "identity"), "clientNonce", "INVALID_IDENTITY");
      strings(payload.capabilities, "capabilities");
      text(payload, "executionTarget");
      break;
    case "host.welcome":
    case "client.detach":
      phase(payload, "session", ["session"]);
      session(payload.session);
      break;
    case "host.snapshot":
      phase(payload, "session", ["session", "viewRevision", "targetRevision", "targetDigest", "assignments", "contributions"]);
      session(payload.session);
      text(payload, "viewRevision");
      positiveLong(payload.targetRevision, "targetRevision");
      digest(payload.targetDigest, "targetDigest");
      assignments(payload.assignments);
      contributions(payload.contributions);
      break;
    case "host.prepare":
    case "host.activate":
    case "host.drain":
    case "host.stop":
      phase(payload, "lifecycle", ["lifecycle"]);
      lifecycle(payload.lifecycle);
      break;
    case "client.lifecycle-result":
      phase(payload, "lifecycle", ["lifecycle", "outcome"]);
      lifecycle(payload.lifecycle);
      lifecycleOutcome(payload.outcome);
      break;
    case "client.observed":
      phase(payload, "session", ["session", "executions"]);
      session(payload.session);
      executions(payload.executions);
      break;
    case "client.call":
      phase(payload, "call", ["call", "contributionKind", "contributionId", "input"]);
      call(payload.call);
      contributionFields(payload);
      literal(payload.input, 3);
      break;
    case "host.call-result":
      phase(payload, "call", ["call", "contributionKind", "contributionId", "outcome"]);
      call(payload.call);
      contributionFields(payload);
      callOutcome(payload.outcome);
      break;
    default:
      throw malformed(`unknown message type ${type}`);
  }
  return { ...envelope, protocolVersion: PROTOCOL_VERSION } as unknown as ProtocolEnvelope;
}

function session(value: JsonValue): SessionFence {
  const fence = object(value, "session", "INVALID_IDENTITY");
  exact(fence, ["hostInstanceId", "clientExecutionId"], "INVALID_IDENTITY");
  text(fence, "hostInstanceId", "INVALID_IDENTITY");
  text(fence, "clientExecutionId", "INVALID_IDENTITY");
  return fence as unknown as SessionFence;
}

function lifecycle(value: JsonValue): LifecycleFence {
  const fence = object(value, "lifecycle", "INVALID_IDENTITY");
  exact(fence, ["session", "targetRevision", "runtimeInstanceId", "lifecycleOperationId"], "INVALID_IDENTITY");
  session(fence.session);
  positiveLong(fence.targetRevision, "targetRevision", "INVALID_IDENTITY");
  text(fence, "runtimeInstanceId", "INVALID_IDENTITY");
  text(fence, "lifecycleOperationId", "INVALID_IDENTITY");
  return fence as unknown as LifecycleFence;
}

function call(value: JsonValue): CallFence {
  const fence = object(value, "call", "INVALID_IDENTITY");
  exact(fence, ["session", "expectedViewRevision", "registrationIdentity"], "INVALID_IDENTITY");
  session(fence.session);
  text(fence, "expectedViewRevision", "INVALID_IDENTITY");
  positiveLong(fence.registrationIdentity, "registrationIdentity", "INVALID_IDENTITY");
  return fence as unknown as CallFence;
}

function assignments(value: JsonValue): void {
  for (const item of array(value, "assignments")) {
    const assignment = object(item, "assignment");
    exact(assignment, ["pluginId", "facetId", "runtimeInstanceId", "executionTarget", "entryModule", "payloadDigest", "requiredCapabilities", "resources"]);
    for (const name of ["pluginId", "facetId", "runtimeInstanceId", "executionTarget", "entryModule"]) text(assignment, name);
    digest(assignment.payloadDigest, "payloadDigest");
    strings(assignment.requiredCapabilities, "requiredCapabilities");
    const paths = new Set<string>();
    for (const resourceValue of array(assignment.resources, "resources")) {
      const resource = object(resourceValue, "resource");
      exact(resource, ["path", "digest", "byteLength"]);
      const path = text(resource, "path");
      if (!normalizedPath(path) || paths.has(path)) {
        throw malformed("path must be a relative logical resource path");
      }
      paths.add(path);
      digest(resource.digest, "digest");
      nonNegativeLong(resource.byteLength, "byteLength");
    }
    if (!paths.has(text(assignment, "entryModule"))) throw malformed("entryModule must reference an assignment resource");
  }
}

function contributions(value: JsonValue): void {
  for (const item of array(value, "contributions")) {
    const contribution = object(item, "contribution");
    exact(contribution, ["contributionKind", "contributionId", "registrationIdentity"]);
    text(contribution, "contributionKind");
    contributionId(contribution.contributionId);
    positiveLong(contribution.registrationIdentity, "registrationIdentity");
  }
}

function contributionFields(value: JsonObject): void {
  text(value, "contributionKind");
  contributionId(value.contributionId);
}

function contributionId(value: JsonValue): ContributionId {
  const id = object(value, "contributionId");
  exact(id, ["providerInstanceId", "localName"]);
  text(id, "providerInstanceId");
  text(id, "localName");
  return id as unknown as ContributionId;
}

function lifecycleOutcome(value: JsonValue): void {
  const outcome = object(value, "outcome");
  const kind = text(outcome, "kind");
  if (kind === "APPLIED") exact(outcome, ["kind"]);
  else if (kind === "FAILED") {
    exact(outcome, ["kind", "failure"]);
    failure(outcome.failure);
  } else throw malformed("invalid lifecycle outcome kind");
}

function callOutcome(value: JsonValue): void {
  const outcome = object(value, "outcome");
  const kind = text(outcome, "kind");
  if (kind === "SUCCESS") {
    exact(outcome, ["kind", "value"]);
    literal(outcome.value, 4);
  } else if (kind === "FAILED") {
    exact(outcome, ["kind", "failure"]);
    failure(outcome.failure);
  } else throw malformed("invalid call outcome kind");
}

function failure(value: JsonValue): ClientError {
  const result = object(value, "failure");
  exact(result, ["code", "message", "diagnostics"]);
  text(result, "code");
  text(result, "message");
  const diagnostics = object(result.diagnostics, "diagnostics");
  for (const [key, entry] of Object.entries(diagnostics)) {
    if (key.length === 0 || typeof entry !== "string" || entry.length === 0) {
      throw malformed("diagnostics must contain non-blank strings");
    }
  }
  return result as unknown as ClientError;
}

function executions(value: JsonValue): void {
  for (const item of array(value, "executions")) {
    const execution = object(item, "execution");
    const state = text(execution, "state");
    const expected = state === "FAILED"
      ? ["targetRevision", "runtimeInstanceId", "lifecycleOperationId", "state", "failure"]
      : ["targetRevision", "runtimeInstanceId", "lifecycleOperationId", "state"];
    exact(execution, expected);
    if (state !== "PENDING" && state !== "ACTIVE" && state !== "FAILED") throw malformed("invalid state");
    positiveLong(execution.targetRevision, "targetRevision");
    text(execution, "runtimeInstanceId");
    text(execution, "lifecycleOperationId");
    if (state === "FAILED") failure(execution.failure);
  }
}

function literal(value: JsonValue, depth: number): LiteralValue {
  if (value === null || typeof value === "boolean" || typeof value === "string") return value;
  if (typeof value === "number" || value instanceof JsonNumber) throw malformed("literal numbers require the NUMBER tag");
  if (Array.isArray(value)) {
    if (depth > MAX_NESTING_DEPTH) throw malformed("literal exceeds nesting depth");
    return value.map((item) => literal(item, depth + 1));
  }
  const tagged = object(value, "literal");
  const kind = text(tagged, "kind");
  if (kind === "NUMBER") {
    if (depth > MAX_NESTING_DEPTH) throw malformed("literal exceeds nesting depth");
    exact(tagged, ["kind", "value"]);
    canonicalDecimal(textual(tagged, "value"));
    return tagged as unknown as LiteralValue;
  }
  if (kind === "OBJECT") {
    exact(tagged, ["kind", "values"]);
    if (depth + 1 > MAX_NESTING_DEPTH) throw malformed("literal exceeds nesting depth");
    const values = object(tagged.values, "values");
    for (const item of Object.values(values)) literal(item, depth + 2);
    return tagged as unknown as LiteralValue;
  }
  throw malformed("invalid literal kind");
}

function positiveLong(value: JsonValue, name: string, code: ErrorCode = "MALFORMED_MESSAGE"): string {
  const result = typeof value === "string" ? value : "";
  if (!/^[1-9][0-9]*$/.test(result)
    || result.length > 19
    || (result.length === 19 && result > "9223372036854775807")) {
    throw new ClientProtocolError(code, `${name} must be a canonical positive signed-long decimal string`);
  }
  return result;
}

function nonNegativeLong(value: JsonValue, name: string): string {
  const result = typeof value === "string" ? value : "";
  if (!/^(0|[1-9][0-9]*)$/.test(result)
    || result.length > 19
    || (result.length === 19 && result > "9223372036854775807")) {
    throw malformed(`${name} must be a canonical non-negative signed-long decimal string`);
  }
  return result;
}

function canonicalDecimal(value: string): void {
  if (value.length > MAX_DECIMAL_CHARACTERS) throw malformed("NUMBER value exceeds character limit");
  if (canonicalizeDecimal(value) !== value) throw malformed("NUMBER value must be canonical");
}

function canonicalizeDecimal(value: string): string | undefined {
  const match = /^(-?)([0-9]+)(?:\.([0-9]+))?(?:E([+-]?[0-9]+))?$/.exec(value);
  if (match === null) return undefined;
  const [, sign, integer, fractional = "", exponentText] = match;
  const exponent = parseExponent(exponentText);
  if (exponent === undefined) return undefined;
  let digits = `${integer}${fractional}`.replace(/^0+/, "");
  if (digits.length === 0) return "0";
  let scale = BigInt(fractional.length) - exponent;
  if (!validScale(scale)) return undefined;
  while (digits.endsWith("0")) {
    digits = digits.slice(0, -1);
    scale -= 1n;
  }
  if (!validScale(scale)) return undefined;
  const adjusted = -scale + BigInt(digits.length) - 1n;
  const prefix = sign === "-" ? "-" : "";
  if (scale >= 0n && adjusted >= -6n) {
    const plainScale = Number(scale);
    if (plainScale >= digits.length) return `${prefix}0.${"0".repeat(plainScale - digits.length)}${digits}`;
    return `${prefix}${digits.slice(0, digits.length - plainScale)}${plainScale === 0 ? "" : `.${digits.slice(digits.length - plainScale)}`}`;
  }
  return `${prefix}${digits[0]}${digits.length === 1 ? "" : `.${digits.slice(1)}`}E${adjusted >= 0n ? "+" : ""}${adjusted}`;
}

function parseExponent(value: string | undefined): bigint | undefined {
  if (value === undefined) return 0n;
  if (!/^[+-]?(?:0|[1-9][0-9]*)$/.test(value)) return undefined;
  return BigInt(value);
}

function validScale(value: bigint): boolean {
  return value >= -2147483648n && value <= 2147483647n;
}

function phase(payload: JsonObject, identity: string, expected: readonly string[]): void {
  const actual = Object.keys(payload);
  if (same(actual, expected)) return;
  const identities = ["identity", "session", "lifecycle", "call"];
  if (!actual.includes(identity) || actual.some((name) => identities.includes(name) && name !== identity)) {
    throw new ClientProtocolError("INVALID_IDENTITY", "missing or mixed phase identity");
  }
  throw malformed("unexpected or missing payload fields");
}

function object(value: JsonValue, name: string, code: ErrorCode = "MALFORMED_MESSAGE"): JsonObject {
  if (value === null || value instanceof JsonNumber || Array.isArray(value) || typeof value !== "object") {
    throw new ClientProtocolError(code, `${name} must be an object`);
  }
  return value;
}

function array(value: JsonValue, name: string): JsonValue[] {
  if (!Array.isArray(value)) throw malformed(`${name} must be an array`);
  return value;
}

function text(value: JsonObject, name: string, code: ErrorCode = "MALFORMED_MESSAGE"): string {
  const result = value[name];
  if (typeof result !== "string" || result.trim().length === 0) {
    throw new ClientProtocolError(code, `${name} must be a non-blank string`);
  }
  return result;
}

function textual(value: JsonObject, name: string, code: ErrorCode = "MALFORMED_MESSAGE"): string {
  const result = value[name];
  if (typeof result !== "string") throw new ClientProtocolError(code, `${name} must be a string`);
  return result;
}

function strings(value: JsonValue, name: string): readonly string[] {
  return array(value, name).map((item) => {
    if (typeof item !== "string" || item.trim().length === 0) throw malformed(`${name} must contain non-blank strings`);
    return item;
  });
}

function digest(value: JsonValue, name: string): string {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value)) throw malformed(`${name} must be a lowercase SHA-256 digest`);
  return value;
}

function normalizedPath(path: string): boolean {
  return path.length > 0 && !path.startsWith("/") && !path.endsWith("/") && !path.includes("\\")
    && !/^[A-Za-z][A-Za-z0-9+.-]*:/.test(path)
    && path.split("/").every((segment) => segment.length > 0 && segment !== "." && segment !== "..");
}

function utf8ByteLength(value: string): number {
  let result = 0;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 0x80) result += 1;
    else if (code < 0x800) result += 2;
    else if (code >= 0xd800 && code <= 0xdbff && index + 1 < value.length
      && value.charCodeAt(index + 1) >= 0xdc00 && value.charCodeAt(index + 1) <= 0xdfff) {
      result += 4;
      index += 1;
    } else result += 3;
  }
  return result;
}

function protocolVersion(value: JsonValue): number {
  if (value instanceof JsonNumber) {
    if (!/^-?(?:0|[1-9][0-9]*)$/.test(value.raw)) throw malformed("protocolVersion must be an integer");
    const integer = BigInt(value.raw);
    if (integer < -2147483648n || integer > 2147483647n) throw malformed("protocolVersion must be an integer");
    return Number(integer);
  }
  if (typeof value === "number" && Number.isInteger(value)) return value;
  throw malformed("protocolVersion must be an integer");
}

function exact(value: JsonObject, expected: readonly string[], code: ErrorCode = "MALFORMED_MESSAGE"): void {
  if (!same(Object.keys(value), expected)) throw new ClientProtocolError(code, "unexpected or missing fields");
}

function same(actual: readonly string[], expected: readonly string[]): boolean {
  return actual.length === expected.length && expected.every((name) => actual.includes(name));
}

function malformed(message: string): ClientProtocolError {
  return new ClientProtocolError("MALFORMED_MESSAGE", message);
}

class StrictJsonReader {
  private index = 0;

  constructor(private readonly source: string) {}

  read(): JsonValue {
    const result = this.value(0);
    this.space();
    if (this.index !== this.source.length) throw malformed("trailing content");
    return result;
  }

  private value(depth: number): JsonValue {
    if (depth > MAX_NESTING_DEPTH) throw malformed("JSON exceeds nesting depth");
    this.space();
    const start = this.source[this.index];
    if (start === "{") return this.object(depth + 1);
    if (start === "[") return this.array(depth + 1);
    if (start === "\"") return this.string();
    if (this.source.startsWith("true", this.index)) { this.index += 4; return true; }
    if (this.source.startsWith("false", this.index)) { this.index += 5; return false; }
    if (this.source.startsWith("null", this.index)) { this.index += 4; return null; }
    return this.number();
  }

  private object(depth: number): JsonObject {
    this.index += 1;
    this.space();
    const result: Record<string, JsonValue> = {};
    const keys = new Set<string>();
    if (this.consume("}")) return result;
    while (true) {
      this.space();
      if (this.source[this.index] !== "\"") throw malformed("object key must be a string");
      const key = this.string();
      if (keys.has(key)) throw malformed("duplicate object field");
      keys.add(key);
      this.space();
      if (!this.consume(":")) throw malformed("object field must have a value");
      Object.defineProperty(result, key, {
        value: this.value(depth), enumerable: true, configurable: true, writable: true,
      });
      this.space();
      if (this.consume("}")) return result;
      if (!this.consume(",")) throw malformed("object fields must be comma-separated");
    }
  }

  private array(depth: number): JsonValue[] {
    this.index += 1;
    this.space();
    const result: JsonValue[] = [];
    if (this.consume("]")) return result;
    while (true) {
      result.push(this.value(depth));
      this.space();
      if (this.consume("]")) return result;
      if (!this.consume(",")) throw malformed("array values must be comma-separated");
    }
  }

  private string(): string {
    const start = this.index;
    this.index += 1;
    while (this.index < this.source.length) {
      const character = this.source[this.index++];
      if (character === "\"") {
        try { return JSON.parse(this.source.slice(start, this.index)) as string; } catch { throw malformed("invalid string"); }
      }
      if (character === "\\") {
        const escaped = this.source[this.index++];
        if (escaped === undefined) throw malformed("invalid string escape");
      } else if (character < " ") {
        throw malformed("invalid string control character");
      }
    }
    throw malformed("unterminated string");
  }

  private number(): JsonNumber {
    const match = /-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(this.source.slice(this.index));
    if (match === null || match.index !== 0) throw malformed("invalid JSON value");
    this.index += match[0].length;
    return new JsonNumber(match[0]);
  }

  private consume(character: string): boolean {
    if (this.source[this.index] !== character) return false;
    this.index += 1;
    return true;
  }

  private space(): void {
    while (this.source[this.index] === " " || this.source[this.index] === "\n"
      || this.source[this.index] === "\r" || this.source[this.index] === "\t") {
      this.index += 1;
    }
  }
}

class JsonNumber {
  constructor(readonly raw: string) {}
  toJSON(): number { return Number(this.raw); }
}
