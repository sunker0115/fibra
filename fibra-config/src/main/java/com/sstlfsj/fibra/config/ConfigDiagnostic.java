package com.sstlfsj.fibra.config;

import java.nio.file.Path;

public record ConfigDiagnostic(ConfigStage stage, String code, String message,
                               Path source, String entryId) {
}
