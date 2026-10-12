package com.sstlfsj.fibra.config;

import com.networknt.schema.path.NodePath;

import java.util.ArrayList;
import java.util.List;

/** 仅从 schema 声明投影路径；动态字典键处立即停止，绝不格式化库 path。 */
final class SchemaDiagnostics {
    private SchemaDiagnostics() { }

    static String pointer(SchemaProfile.Admission admission, NodePath location) {
        if (location == null || location.getNameCount() > 64) return "";
        var nodes = List.of(admission.root());
        var pointer = new StringBuilder();
        for (var index = 0; index < location.getNameCount(); index++) {
            var segment = location.getElement(index);
            var alternatives = new ArrayList<SchemaProfile.Node>();
            for (var node : nodes) flatten(node, admission, alternatives);
            var children = new ArrayList<SchemaProfile.Node>();
            for (var node : alternatives) {
                SchemaProfile.Node child;
                if (segment instanceof Integer number && number >= 0 && number < 10000) {
                    child = node.items();
                } else if (segment instanceof String name) {
                    child = node.properties().get(name);
                } else return pointer.toString();
                // 任一合法路径可能是动态字典键时，保守停留在共同静态父节点。
                if (child == null) return pointer.toString();
                children.add(child);
            }
            if (children.isEmpty()) return pointer.toString();
            var token = segment.toString().replace("~", "~0").replace("/", "~1");
            if (pointer.length() + token.length() + 1 > 1024) return pointer.toString();
            pointer.append('/').append(token);
            nodes = children;
        }
        return pointer.toString();
    }

    private static void flatten(SchemaProfile.Node node, SchemaProfile.Admission admission,
                                List<SchemaProfile.Node> result) {
        if (node.reference() != null) flatten(admission.definitions().get(node.reference()), admission, result);
        else if (node.combination()) {
            for (var child : node.children()) flatten(child, admission, result);
        } else result.add(node);
    }
}
