package com.sstlfsj.fibra.plugins.fs.search;

import com.sstlfsj.fibra.plugins.tool.ToolException;
import com.sstlfsj.fibra.plugins.tool.ToolFailureCode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SearchOutputParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SearchOutputParser() {
    }

    static List<String> glob(String output) {
        return output.lines().filter(line -> !line.isEmpty()).toList();
    }

    static List<SearchMatch> grep(String output) {
        var matches = new ArrayList<SearchMatch>();
        for (var line : output.split("\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var record = object(read(line), "record");
            if (!"match".equals(record.get("type"))) {
                continue;
            }
            var data = object(record.get("data"), "match data");
            var path = text(object(data.get("path"), "match path").get("text"),
                "match path text");
            var lineNumber = integer(data.get("line_number"), "match line number");
            var lines = object(data.get("lines"), "match lines");
            String content;
            if (lines.get("text") instanceof String value) {
                content = stripLineEnd(value);
            } else if (lines.get("bytes") instanceof String) {
                content = "(line is not valid UTF-8)";
            } else {
                throw malformed("match lines contain neither text nor bytes", null);
            }
            matches.add(new SearchMatch(path, lineNumber, content));
        }
        return List.copyOf(matches);
    }

    private static Object read(String line) {
        try {
            return JSON.readValue(line, Object.class);
        } catch (Exception failure) {
            throw malformed("a line is not JSON", failure);
        }
    }

    private static Map<?, ?> object(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw malformed(name + " is not an object", null);
    }

    private static String text(Object value, String name) {
        if (value instanceof String text) {
            return text;
        }
        throw malformed(name + " is not text", null);
    }

    private static int integer(Object value, String name) {
        if (value instanceof Number number) {
            var result = number.intValue();
            if (result > 0 && number.doubleValue() == result) {
                return result;
            }
        }
        throw malformed(name + " is not a positive integer", null);
    }

    private static String stripLineEnd(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    private static ToolException malformed(String detail, Throwable cause) {
        var message = "grep received malformed ripgrep JSON output: " + detail;
        return cause == null
            ? new ToolException(ToolFailureCode.SEARCH_FAILED, message)
            : new ToolException(ToolFailureCode.SEARCH_FAILED, message, cause);
    }
}
