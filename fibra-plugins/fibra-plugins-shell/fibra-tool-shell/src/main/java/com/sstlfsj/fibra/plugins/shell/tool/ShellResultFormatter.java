package com.sstlfsj.fibra.plugins.shell.tool;

import com.sstlfsj.fibra.plugins.shell.ShellOutput;
import com.sstlfsj.fibra.plugins.shell.ShellResult;

final class ShellResultFormatter {
    private ShellResultFormatter() {
    }

    static String format(ShellResult result) {
        var body = streamText(result.stdout());
        var stderr = streamText(result.stderr());
        if (!stderr.isEmpty()) {
            if (!body.isEmpty() && !body.endsWith("\n")) {
                body += "\n";
            }
            body += "[stderr]\n" + stderr;
        }
        if (body.isEmpty()) {
            body = "(no output)";
        }

        String marker = null;
        if (result.signal() != null) {
            marker = "[killed by signal: " + result.signal() + "]";
        } else if (result.exitCode() != 0) {
            marker = "[exit code: " + result.exitCode() + "]";
        }
        if (marker == null) {
            return body;
        }
        return body + (body.endsWith("\n") ? "" : "\n") + marker;
    }

    private static String streamText(ShellOutput output) {
        return output.truncated()
            ? output.text() + "\n[output truncated; full output: (unavailable)]"
            : output.text();
    }
}
