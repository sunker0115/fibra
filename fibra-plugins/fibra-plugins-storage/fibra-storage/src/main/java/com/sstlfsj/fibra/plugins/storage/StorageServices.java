package com.sstlfsj.fibra.plugins.storage;

import com.sstlfsj.fibra.ServiceKey;

public final class StorageServices {
    public static final ServiceKey<ConfigStore> CONFIG_STORE =
        ServiceKey.of("fibra.storage", ConfigStore.class);

    private StorageServices() {
    }
}
