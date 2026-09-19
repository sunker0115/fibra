package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.PluginId;
import com.sstlfsj.fibra.config.*;
import com.sstlfsj.fibra.value.LiteralValue;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 唯一完整目标格式；严格字段、摘要与 canonical 编码共同拒绝旧格式及歧义输入。 */
public final class DeploymentTargetCodec {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

    private DeploymentTargetCodec() { }

    public static byte[] encode(DeploymentTarget target) {
        var content = LiteralValue.of(JSON.readValue(DeploymentTarget.canonicalBytes(
            target.selections().values(), target.desiredGraph(), target.configContext()), Object.class));
        return LiteralValue.of(Map.of("targetRevision", target.targetRevision(),
            "targetDigest", target.targetDigest(), "target", content))
            .canonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    public static DeploymentTarget decode(byte[] bytes) {
        try {
            var envelope = object(LiteralValue.of(JSON.readValue(bytes, Object.class)));
            fields(envelope, Set.of("targetRevision", "targetDigest", "target"));
            if (!(envelope.get("targetRevision") instanceof LiteralValue.NumberValue revision)) {
                throw new IllegalArgumentException("target revision must be an integer");
            }
            var content = object(envelope.get("target"));
            fields(content, Set.of("format", "selections", "desired", "configContext"));
            if (!LiteralValue.of(1).equals(content.get("format"))) {
                throw new IllegalArgumentException("unsupported deployment target format");
            }
            var selections = list(content.get("selections")).stream().map(value -> {
                var selection = object(value);
                fields(selection, Set.of("pluginId", "packageRevision", "enabled"));
                return new PluginSelection(new PluginId(text(selection.get("pluginId"))),
                    text(selection.get("packageRevision")), bool(selection.get("enabled")));
            }).toList();
            var target = DeploymentTarget.of(revision.value().longValueExact(), selections,
                new DesiredInputGraph(nodes(content.get("desired"))),
                ConfigContextSnapshot.of(new LiteralValue.ObjectValue(object(content.get("configContext")))));
            if (!target.targetDigest().equals(text(envelope.get("targetDigest")))) {
                throw new IllegalArgumentException("deployment target digest mismatch");
            }
            if (!Arrays.equals(bytes, encode(target))) {
                throw new IllegalArgumentException("deployment target encoding is not canonical");
            }
            return target;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid deployment target", failure);
        }
    }

    private static List<DesiredInputNode> nodes(LiteralValue value) {
        return list(value).stream().map(DeploymentTargetCodec::node).toList();
    }

    private static DesiredInputNode node(LiteralValue value) {
        var values = object(value);
        var id = text(values.get("id"));
        var enabled = bool(values.get("enabled"));
        var when = values.get("when");
        var context = object(values.get("context"));
        var realms = object(values.get("realms"));
        var intercepts = object(values.get("intercepts"));
        return switch (text(values.get("kind"))) {
            case "plugin" -> {
                fields(values, Set.of("kind", "id", "enabled", "when", "context", "realms",
                    "intercepts", "definition", "config", "publicationRequirement"));
                var ref = object(values.get("definition"));
                fields(ref, Set.of("pluginId", "facetId", "definitionId"));
                yield DesiredInputEntry.builder(id, new PluginDefinitionRef(text(ref.get("pluginId")),
                        text(ref.get("facetId")), text(ref.get("definitionId"))))
                    .enabled(enabled).when(when).context(context).realms(realms).intercepts(intercepts)
                    .config(values.get("config")).publicationRequirement(PublicationRequirement.valueOf(
                        text(values.get("publicationRequirement")))).build();
            }
            case "group" -> {
                fields(values, Set.of("kind", "id", "enabled", "when", "context", "realms",
                    "intercepts", "children"));
                yield DesiredInputGroup.builder(id).enabled(enabled).when(when).context(context)
                    .realms(realms).intercepts(intercepts).children(nodes(values.get("children"))).build();
            }
            case "include" -> {
                fields(values, Set.of("kind", "id", "enabled", "when", "context", "realms",
                    "intercepts", "content"));
                var include = object(values.get("content"));
                var child = switch (text(include.get("state"))) {
                    case "collected" -> {
                        fields(include, Set.of("state", "children"));
                        yield new DesiredIncludeContent.Collected(nodes(include.get("children")));
                    }
                    case "uncollected" -> {
                        fields(include, Set.of("state"));
                        yield new DesiredIncludeContent.Uncollected();
                    }
                    default -> throw new IllegalArgumentException("unknown include state");
                };
                yield DesiredInputInclude.builder(id).enabled(enabled).when(when).context(context)
                    .realms(realms).intercepts(intercepts).content(child).build();
            }
            default -> throw new IllegalArgumentException("unknown desired node kind");
        };
    }

    private static Map<String, LiteralValue> object(LiteralValue value) {
        if (value instanceof LiteralValue.ObjectValue object) return object.values();
        throw new IllegalArgumentException("expected an object");
    }
    private static List<LiteralValue> list(LiteralValue value) {
        if (value instanceof LiteralValue.ListValue list) return list.values();
        throw new IllegalArgumentException("expected a list");
    }
    private static String text(LiteralValue value) {
        if (value instanceof LiteralValue.StringValue text) return text.value();
        throw new IllegalArgumentException("expected a string");
    }
    private static boolean bool(LiteralValue value) {
        if (value instanceof LiteralValue.BooleanValue bool) return bool.value();
        throw new IllegalArgumentException("expected a boolean");
    }
    private static void fields(Map<String, LiteralValue> value, Set<String> expected) {
        if (!value.keySet().equals(expected)) throw new IllegalArgumentException(
            "deployment target fields must be exactly " + expected);
    }
}
