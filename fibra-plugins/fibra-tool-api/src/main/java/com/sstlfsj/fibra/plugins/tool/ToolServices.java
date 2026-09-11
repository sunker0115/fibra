package com.sstlfsj.fibra.plugins.tool;

import com.sstlfsj.fibra.ServiceKey;

public final class ToolServices {
    public static final ServiceKey<ResultSpillStore> RESULT_SPILL_STORE =
        ServiceKey.of("fibra.tool.result-spill-store", ResultSpillStore.class);

    private ToolServices() {
    }
}
