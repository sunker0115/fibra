package com.sstlfsj.fibra.config;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** fibra-config-1 的纯编译与 effective literal 校验入口。 */
public final class ConfigSchema {
    private final SchemaProfile.Admission admission;
    private final com.networknt.schema.Schema schema;

    private ConfigSchema(ConfigSchemaSource source, SchemaProfile.Admission admission) {
        this.admission = admission;
        this.schema = SchemaLibrary.compile(source);
    }

    /** 检查单份 source 的语言、预算及固定 meta-schema；拒绝时抛出安全异常。 */
    public static ConfigSchema compile(ConfigSchemaSource source) {
        Objects.requireNonNull(source, "source");
        return new ConfigSchema(source, new SchemaProfile().admit(source));
    }

    /** 只检查已求值 literal，不转换、不补默认、不改写输入；此入口只承诺单实例预算。 */
    public Optional<ConfigViolation> validate(LiteralValue effectiveValue) {
        Objects.requireNonNull(effectiveValue, "effectiveValue");
        try {
            if (SchemaBudget.work(admission.expanded(), admission.combinations(), SchemaBudget.effective(effectiveValue))
                > SchemaBudget.ENTRY_WORK) throw SchemaBudget.exceeded();
            return SchemaLibrary.validate(schema, admission, effectiveValue);
        } catch (ConfigSchemaException exception) {
            return Optional.of(violation(exception));
        }
    }

    /** 发布资格入口：先接纳整个集合，再按顺序编译全部唯一 source。 */
    public static Optional<ConfigBatchFailure> checkSources(List<ConfigSchemaSource> sources) {
        Objects.requireNonNull(sources, "sources");
        var batch = new Batch();
        var index = 0;
        for (var source : sources) {
            var failure = batch.admit(Objects.requireNonNull(source, "source"), null, index++);
            if (failure != null) return Optional.of(failure);
        }
        return batch.checkSources();
    }

    /** 命令入口：先检查全部输入的接纳预算，再编译/校验；重复实例仍逐次计费。 */
    public static Optional<ConfigBatchFailure> validateAll(List<ConfigValidationInput> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        var batch = new Batch();
        var index = 0;
        for (var input : inputs) {
            Objects.requireNonNull(input, "input");
            var failure = batch.admit(input.source(), input, index++);
            if (failure != null) return Optional.of(failure);
        }
        return batch.validate();
    }

    /** 每次调用的接纳状态；不物化调用者整个集合，不保存已知语义失败后的实例。 */
    private static final class Batch {
        private final LinkedHashMap<ConfigSchemaSource, SourceAdmission> sources = new LinkedHashMap<>();
        private final List<ConfigValidationInput> executable = new ArrayList<>();
        private ConfigBatchFailure firstSemanticFailure;
        private long bytes;
        private long total;

        private ConfigBatchFailure admit(ConfigSchemaSource source, ConfigValidationInput input, int index) {
            var accepted = sources.get(source);
            if (accepted == null) {
                bytes = SchemaBudget.add(bytes, SchemaBudget.source(source.schema()).bytes(), SchemaBudget.MIB);
                if (bytes > SchemaBudget.MIB || sources.size() >= 128) return collectionBudget();
                try {
                    accepted = new SourceAdmission(index, new SchemaProfile().admit(source), null);
                } catch (ConfigSchemaException exception) {
                    if (exception.code() == ConfigSchemaException.Code.BUDGET_EXCEEDED) return failure(index, exception);
                    accepted = new SourceAdmission(index, null, violation(exception));
                    if (firstSemanticFailure == null) firstSemanticFailure = failure(index, exception);
                }
                sources.put(source, accepted);
            }
            if (input != null) {
                try {
                    var stats = SchemaBudget.effective(input.effectiveValue());
                    var admission = accepted.admission();
                    if (admission != null) {
                        var work = SchemaBudget.work(admission.expanded(), admission.combinations(), stats);
                        if (work > SchemaBudget.ENTRY_WORK) throw SchemaBudget.exceeded();
                        total = SchemaBudget.add(total, work, SchemaBudget.BATCH_WORK);
                        if (total > SchemaBudget.BATCH_WORK) return collectionBudget();
                    }
                } catch (ConfigSchemaException exception) {
                    return failure(index, exception);
                }
                // 此前缀每项均已计入总 U；immutable record 捕获后执行阶段不再读调用方 List。
                if (firstSemanticFailure == null) executable.add(input);
            }
            return null;
        }

        private Optional<ConfigBatchFailure> checkSources() {
            // unique 映射按首次出现顺序保存，重复 source 不复制也不重复编译。
            for (var entry : sources.entrySet()) {
                var accepted = entry.getValue();
                if (accepted.rejection() != null) {
                    return Optional.of(new ConfigBatchFailure(accepted.inputIndex(), accepted.rejection()));
                }
                try {
                    new ConfigSchema(entry.getKey(), accepted.admission());
                } catch (ConfigSchemaException exception) {
                    return Optional.of(failure(accepted.inputIndex(), exception));
                }
            }
            return Optional.empty();
        }

        private Optional<ConfigBatchFailure> validate() {
            var compiled = new LinkedHashMap<ConfigSchemaSource, ConfigSchema>();
            for (var index = 0; index < executable.size(); index++) {
                var input = executable.get(index);
                try {
                    var rule = compiled.computeIfAbsent(input.source(),
                        key -> new ConfigSchema(key, sources.get(key).admission()));
                    var failure = SchemaLibrary.validate(rule.schema, rule.admission, input.effectiveValue());
                    if (failure.isPresent()) return Optional.of(new ConfigBatchFailure(index, failure.orElseThrow()));
                } catch (ConfigSchemaException exception) {
                    return Optional.of(failure(index, exception));
                }
            }
            return Optional.ofNullable(firstSemanticFailure);
        }
    }

    private record SourceAdmission(int inputIndex, SchemaProfile.Admission admission, ConfigViolation rejection) { }

    private static ConfigViolation violation(ConfigSchemaException exception) {
        return new ConfigViolation(exception.code().name(), "", "");
    }

    private static ConfigBatchFailure failure(int index, ConfigSchemaException exception) {
        return new ConfigBatchFailure(index, violation(exception));
    }

    private static ConfigBatchFailure collectionBudget() {
        return new ConfigBatchFailure(-1, new ConfigViolation("BUDGET_EXCEEDED", "", ""));
    }
}
