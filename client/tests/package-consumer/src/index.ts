import type {
  ClientEntryModule,
  ClientInstanceContext,
  ClientModule,
  LiteralObject,
} from "@sstlfsj/fibra-client-api";
import { decodeEnvelope, encodeEnvelope, PROTOCOL_VERSION } from "@sstlfsj/fibra-client-protocol";

const entryModule = {
  definitions: [
    {
      definitionId: "panel",
      create(context: ClientInstanceContext): ClientModule {
        const config = context.config as LiteralObject;
        void context.desiredEntryId;
        void context.definitionId;
        void context.unitTargetRevision;
        void context.runtimeInstanceId;
        void config;
        return { activate() {} };
      },
    },
    {
      definitionId: "toolbar",
      create(): ClientModule {
        return { prepare() {}, drain() {}, stop() {} };
      },
    },
  ],
} satisfies ClientEntryModule;

void entryModule;

const wire = '{"protocolVersion":1,"messageId":"consumer","type":"client.detach","payload":{"session":{"hostInstanceId":"host","clientExecutionId":"client"}}}';
const result = encodeEnvelope(decodeEnvelope(wire));
if (PROTOCOL_VERSION !== 1 || result !== wire) {
  throw new Error("packed client protocol is not consumable");
}
