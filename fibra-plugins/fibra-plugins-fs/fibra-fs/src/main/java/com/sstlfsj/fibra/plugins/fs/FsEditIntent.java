package com.sstlfsj.fibra.plugins.fs;

import java.util.Objects;

public sealed interface FsEditIntent permits FsEditIntent.Unconditional,
        FsEditIntent.ReplaceIfVersion {
    record Unconditional() implements FsEditIntent {
    }

    record ReplaceIfVersion(FsVersion version) implements FsEditIntent {
        public ReplaceIfVersion {
            Objects.requireNonNull(version, "version");
        }
    }
}
