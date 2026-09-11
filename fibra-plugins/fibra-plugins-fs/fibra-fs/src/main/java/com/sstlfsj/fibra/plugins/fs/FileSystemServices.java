package com.sstlfsj.fibra.plugins.fs;

import com.sstlfsj.fibra.ServiceKey;

public final class FileSystemServices {
    public static final ServiceKey<FileSystem> FILE_SYSTEM = ServiceKey.of("fibra.fs", FileSystem.class);

    private FileSystemServices() {
    }
}
