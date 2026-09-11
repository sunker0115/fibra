package com.sstlfsj.fibra.plugins.shell;

import com.sstlfsj.fibra.ServiceKey;

public final class ShellServices {
    public static final ServiceKey<Shell> SHELL = ServiceKey.of("fibra.shell", Shell.class);

    private ShellServices() {
    }
}
