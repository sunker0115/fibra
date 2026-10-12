package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ManagedFacet;
import com.sstlfsj.fibra.artifact.PluginConfigurationResource;
import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.artifact.PluginPackageRecord;
import com.sstlfsj.fibra.artifact.RuntimeId;
import com.sstlfsj.fibra.config.ConfigSchemaSource;
import com.sstlfsj.fibra.config.DefinitionConfigDocument;
import com.sstlfsj.fibra.config.PluginDefinitionRef;
import com.sstlfsj.fibra.engine.deployment.BuiltInPluginPackage;
import com.sstlfsj.fibra.engine.deployment.ConfigContractException;
import com.sstlfsj.fibra.engine.deployment.FacetDefinitionNames;
import com.sstlfsj.fibra.engine.runtime.RuntimeDriver;
import reactor.core.publisher.Mono;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import static com.sstlfsj.fibra.engine.deployment.ConfigContractException.Code.INVALID_CONTRACT;
import static com.sstlfsj.fibra.engine.deployment.ConfigContractException.Code.MISSING_CONTRACT;
import static com.sstlfsj.fibra.engine.deployment.ConfigContractException.Code.PACKAGE_UNAVAILABLE;
import static com.sstlfsj.fibra.engine.deployment.ConfigContractException.Code.READ_FAILED;
import static com.sstlfsj.fibra.engine.deployment.ConfigContractException.Code.UNKNOWN_DEFINITION;
import static com.sstlfsj.fibra.engine.deployment.ConfigContractException.Code.UNSUPPORTED_RUNTIME;

/** 一次读取中的纯名称/文档复用；无跨命令缓存，不拥有 runtime 或 artifact 生命周期。 */
final class EngineConfigurationContracts {
    static final String INTERPRETER_IDENTITY = "fibra-config-1:interpreter-1";
    private final EngineCommandLoop loop;
    private final Map<RuntimeId, RuntimeDriver> drivers;
    private final Function<PluginPackageRecord, PluginConfigurationResource> resources;

    EngineConfigurationContracts(EngineCommandLoop loop, Map<RuntimeId, RuntimeDriver> drivers,
                                 Function<PluginPackageRecord, PluginConfigurationResource> resources) {
        this.loop = loop;
        this.drivers = drivers;
        this.resources = resources;
    }

    Read read(EngineReadAttempt owner, List<PluginPackageRecord> records, List<BuiltInPluginPackage> builtIns) {
        return new Read(owner, records, builtIns);
    }

    final class Read {
        private final EngineReadAttempt owner;
        private final List<PluginPackageRecord> records;
        private final List<BuiltInPluginPackage> builtIns;
        private final Map<ManagedFacet, List<String>> names = new HashMap<>();
        private final Map<PluginId, DefinitionConfigDocument> documents = new HashMap<>();

        Read(EngineReadAttempt owner, List<PluginPackageRecord> records, List<BuiltInPluginPackage> builtIns) {
            this.owner = owner; this.records = records; this.builtIns = builtIns;
        }

        Mono<Void> requireName(PluginDefinitionRef ref) {
            return Mono.defer(() -> {
                var builtin = builtin(ref);
                if (builtin != null) {
                    var facet = builtin.facets().stream().filter(f -> f.facetId().value().equals(ref.facetId()))
                        .findFirst().orElseThrow(() -> error(UNKNOWN_DEFINITION));
                    if (!drivers.containsKey(facet.runtimeId())) throw error(UNSUPPORTED_RUNTIME);
                    if (!facet.definitionIds().contains(ref.definitionId())) throw error(UNKNOWN_DEFINITION);
                    return Mono.empty();
                }
                var facet = record(ref).managedPackage().facets().stream()
                    .filter(f -> f.facet().facetId().value().equals(ref.facetId()))
                    .findFirst().orElseThrow(() -> error(UNKNOWN_DEFINITION));
                var driver = drivers.get(facet.facet().runtimeId());
                if (driver == null) throw error(UNSUPPORTED_RUNTIME);
                var known = names.get(facet);
                if (known != null) return requireDefinition(known, ref);
                final Mono<List<String>> result;
                try {
                    var query = owner.register(driver.createDefinitionQuery(facet));
                    result = Objects.requireNonNull(query.readAsync(), "definition read");
                } catch (RuntimeException | Error foreign) {
                    EngineReadAttempt.rethrowFatal(foreign);
                    throw error(READ_FAILED);
                }
                return result.onErrorMap(foreign -> {
                        EngineReadAttempt.rethrowFatal(foreign);
                        return error(READ_FAILED);
                    })
                    .switchIfEmpty(Mono.error(error(READ_FAILED)))
                    .flatMap(ids -> loop.call(() -> {
                        final List<String> value;
                        try {
                            value = new FacetDefinitionNames(facet.facet().facetId(), facet.facet().runtimeId(),
                                facet.facet().executionTarget(), ids).definitionIds();
                        } catch (RuntimeException | Error foreign) {
                            EngineReadAttempt.rethrowFatal(foreign);
                            throw error(READ_FAILED);
                        }
                        names.put(facet, value);
                        return requireDefinition(value, ref);
                    }));
            }).onErrorMap(EngineConfigurationContracts::safeReadFailure).then();
        }

        ConfigSchemaSource source(PluginDefinitionRef ref) {
            try {
                var builtin = builtin(ref);
                if (builtin != null) {
                    var facet = builtin.facets().stream().filter(f -> f.facetId().value().equals(ref.facetId()))
                        .findFirst().orElseThrow(() -> error(UNKNOWN_DEFINITION));
                    return requireSource(facet.configSchemas().get(ref.definitionId()));
                }
                var record = record(ref);
                var document = documents.get(record.pluginId());
                if (document == null) {
                    final PluginConfigurationResource resource;
                    try { resource = owner.register(resources.apply(record)); }
                    catch (RuntimeException | Error foreign) {
                        EngineReadAttempt.rethrowFatal(foreign);
                        throw error(READ_FAILED);
                    }
                    final byte[] bytes;
                    try { bytes = Objects.requireNonNull(resource.read(), "configuration bytes"); }
                    catch (NoSuchElementException absent) { throw error(MISSING_CONTRACT); }
                    catch (RuntimeException | Error foreign) {
                        EngineReadAttempt.rethrowFatal(foreign);
                        throw error(READ_FAILED);
                    }
                    try { document = DefinitionConfigDocument.parse(bytes); }
                    catch (RuntimeException | Error invalid) {
                        EngineReadAttempt.rethrowFatal(invalid);
                        throw error(INVALID_CONTRACT);
                    }
                    documents.put(record.pluginId(), document);
                }
                return requireSource(document.facets().getOrDefault(ref.facetId(), Map.of()).get(ref.definitionId()));
            } catch (RuntimeException | Error foreign) { throw safeReadFailure(foreign); }
        }

        private BuiltInPluginPackage builtin(PluginDefinitionRef ref) {
            return builtIns.stream().filter(b -> b.pluginId().value().equals(ref.pluginId())).findFirst().orElse(null);
        }

        private PluginPackageRecord record(PluginDefinitionRef ref) {
            return records.stream().filter(r -> r.pluginId().value().equals(ref.pluginId()))
                .findFirst().orElseThrow(() -> error(PACKAGE_UNAVAILABLE));
        }
    }

    private static Mono<Void> requireDefinition(List<String> names, PluginDefinitionRef ref) {
        return names.contains(ref.definitionId()) ? Mono.empty() : Mono.error(error(UNKNOWN_DEFINITION));
    }
    private static ConfigSchemaSource requireSource(ConfigSchemaSource source) {
        if (source == null) throw error(MISSING_CONTRACT);
        return source;
    }
    static ConfigContractException error(ConfigContractException.Code code) {
        return new ConfigContractException(code, "configuration contract failed: " + code.name());
    }
    static ConfigContractException safeReadFailure(Throwable foreign) {
        EngineReadAttempt.rethrowFatal(foreign);
        return foreign instanceof ConfigContractException safe ? safe : error(READ_FAILED);
    }
}
