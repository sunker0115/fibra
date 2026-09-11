package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.artifact.ArtifactId;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.DesiredInputNode;
import com.sstlfsj.fibra.config.DesiredInputGroup;
import com.sstlfsj.fibra.config.DesiredInputInclude;
import com.sstlfsj.fibra.config.DesiredIncludeContent;
import com.sstlfsj.fibra.config.PublicationRequirement;
import com.sstlfsj.fibra.value.LiteralValue;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 版本化存储格式；只接受完整、无歧义的清单，不为未知格式补默认值。 */
final class DeploymentManifestCodec {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

    private DeploymentManifestCodec() { }

    static byte[] encode(DeploymentManifest manifest) {
        var artifacts = new LinkedHashMap<String, LiteralValue>();
        manifest.artifacts().forEach((id, revision) -> artifacts.put(id.value(), LiteralValue.of(revision)));
        return LiteralValue.of(Map.of("format", 2, "artifacts", new LiteralValue.ObjectValue(artifacts),
            "desired", encodeNodes(manifest.desiredGraph().roots())))
            .canonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    static DeploymentManifest decode(byte[] content) {
        try {
            var root = object(LiteralValue.of(JSON.readValue(content, Object.class)));
            requireFields(root, Set.of("format", "artifacts", "desired"));
            if (!LiteralValue.of(2).equals(root.get("format"))) {
                throw new IllegalArgumentException("unsupported deployment manifest format");
            }
            var artifacts = new LinkedHashMap<ArtifactId, String>();
            object(root.get("artifacts")).forEach((id, revision) ->
                artifacts.put(new ArtifactId(id), text(revision)));
            return new DeploymentManifest(artifacts,
                new DesiredInputGraph(decodeNodes(root.get("desired"))));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid deployment manifest", failure);
        }
    }

    private static LiteralValue encodeNodes(List<DesiredInputNode> nodes) {
        return new LiteralValue.ListValue(nodes.stream().map(DeploymentManifestCodec::encodeNode).toList());
    }

    private static LiteralValue encodeNode(DesiredInputNode node) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("id", node.id());
        fields.put("enabled", node.enabled());
        fields.put("realms", new LiteralValue.ObjectValue(node.realms()));
        fields.put("intercepts", new LiteralValue.ObjectValue(node.intercepts()));
        switch (node) {
            case DesiredInputEntry plugin -> {
                fields.put("kind", "plugin");
                fields.put("definitionName", plugin.definitionName());
                fields.put("config", plugin.config());
                fields.put("publicationRequirement", plugin.publicationRequirement().name());
            }
            case DesiredInputGroup group -> {
                fields.put("kind", "group");
                fields.put("children", encodeNodes(group.children()));
            }
            case DesiredInputInclude include -> {
                fields.put("kind", "include");
                fields.put("content", switch (include.content()) {
                    case DesiredIncludeContent.Collected collected -> Map.of(
                        "state", "collected", "children", encodeNodes(collected.children()));
                    case DesiredIncludeContent.Uncollected ignored -> Map.of("state", "uncollected");
                });
            }
        }
        return LiteralValue.of(fields);
    }

    private static List<DesiredInputNode> decodeNodes(LiteralValue value) {
        if (!(value instanceof LiteralValue.ListValue nodes)) {
            throw new IllegalArgumentException("nodes must be a list");
        }
        return nodes.values().stream().map(DeploymentManifestCodec::decodeNode).toList();
    }

    private static DesiredInputNode decodeNode(LiteralValue value) {
        var fields = object(value);
        if (!(fields.get("enabled") instanceof LiteralValue.BooleanValue enabled)) {
            throw new IllegalArgumentException("enabled must be a boolean");
        }
        var id = text(fields.get("id"));
        var realms = object(fields.get("realms"));
        var intercepts = object(fields.get("intercepts"));
        return switch (text(fields.get("kind"))) {
            case "plugin" -> {
                requireFields(fields, Set.of("kind", "id", "enabled", "realms", "intercepts",
                    "definitionName", "config", "publicationRequirement"));
                yield DesiredInputEntry.builder(id, text(fields.get("definitionName")))
                    .enabled(enabled.value()).realms(realms).intercepts(intercepts)
                    .config(fields.get("config"))
                    .publicationRequirement(PublicationRequirement.valueOf(
                        text(fields.get("publicationRequirement")))).build();
            }
            case "group" -> {
                requireFields(fields, Set.of("kind", "id", "enabled", "realms", "intercepts", "children"));
                yield DesiredInputGroup.builder(id).enabled(enabled.value()).realms(realms)
                    .intercepts(intercepts).children(decodeNodes(fields.get("children"))).build();
            }
            case "include" -> {
                requireFields(fields, Set.of("kind", "id", "enabled", "realms", "intercepts", "content"));
                yield DesiredInputInclude.builder(id).enabled(enabled.value()).realms(realms)
                    .intercepts(intercepts).content(decodeInclude(fields.get("content"))).build();
            }
            default -> throw new IllegalArgumentException("unknown desired node kind");
        };
    }

    private static DesiredIncludeContent decodeInclude(LiteralValue value) {
        var fields = object(value);
        return switch (text(fields.get("state"))) {
            case "collected" -> {
                requireFields(fields, Set.of("state", "children"));
                yield new DesiredIncludeContent.Collected(decodeNodes(fields.get("children")));
            }
            case "uncollected" -> {
                requireFields(fields, Set.of("state"));
                yield new DesiredIncludeContent.Uncollected();
            }
            default -> throw new IllegalArgumentException("unknown include content state");
        };
    }

    private static Map<String, LiteralValue> object(LiteralValue value) {
        if (value instanceof LiteralValue.ObjectValue object) return object.values();
        throw new IllegalArgumentException("expected an object");
    }

    private static String text(LiteralValue value) {
        if (value instanceof LiteralValue.StringValue text) return text.value();
        throw new IllegalArgumentException("expected a string");
    }

    private static void requireFields(Map<String, LiteralValue> fields, Set<String> expected) {
        if (!fields.keySet().equals(expected)) {
            throw new IllegalArgumentException("manifest fields must be exactly " + expected);
        }
    }
}
