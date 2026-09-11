package com.sstlfsj.fibra.plugins.fs;

import java.util.Objects;

public sealed interface FsWriteIntent permits FsWriteIntent.Unconditional,
        FsWriteIntent.CreateIfAbsent, FsWriteIntent.ReplaceIfVersion {
    record Unconditional() implements FsWriteIntent {
    }

    record CreateIfAbsent() implements FsWriteIntent {
    }

    record ReplaceIfVersion(FsVersion version) implements FsWriteIntent {
        public ReplaceIfVersion {
            Objects.requireNonNull(version, "version");
        }
    }
}
