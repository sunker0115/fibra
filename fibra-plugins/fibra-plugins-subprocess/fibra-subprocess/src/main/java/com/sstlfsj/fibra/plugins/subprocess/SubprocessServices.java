package com.sstlfsj.fibra.plugins.subprocess;

import com.sstlfsj.fibra.ServiceKey;

public final class SubprocessServices {
    public static final ServiceKey<Subprocess> SUBPROCESS =
        ServiceKey.of("fibra.subprocess", Subprocess.class);

    private SubprocessServices() {
    }
}
