import type {
  ClientEntryModule,
  ClientInstanceContext,
  ClientModule,
  LiteralObject,
} from "../src/index.js";

const entryModule = {
  definitions: [
    {
      definitionId: "panel",
      create(context: ClientInstanceContext): ClientModule {
        const config: LiteralObject = context.config as LiteralObject;
        void context.desiredEntryId;
        void context.definitionId;
        void context.unitTargetRevision;
        void context.runtimeInstanceId;
        void context.scope;
        void context.host;
        void config;
        return {
          prepare() {},
          activate() {},
          drain() {},
          stop() {},
        };
      },
    },
  ],
} satisfies ClientEntryModule;

const module = entryModule.definitions[0]!.create({} as ClientInstanceContext);
void module;

declare const lifecycle: ClientModule;
lifecycle.prepare?.();
lifecycle.activate?.();
lifecycle.drain?.();
lifecycle.stop?.();

// @ts-expect-error Lifecycle context is bound once by ClientModuleDefinition.create.
lifecycle.activate?.({} as ClientInstanceContext);
