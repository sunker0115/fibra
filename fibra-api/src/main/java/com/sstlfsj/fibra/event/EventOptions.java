package com.sstlfsj.fibra.event;

public final class EventOptions {
    private static final EventOptions DEFAULT = new EventOptions(false, false);
    private static final EventOptions PREPEND = new EventOptions(true, false);
    private static final EventOptions GLOBAL = new EventOptions(false, true);
    private static final EventOptions PREPEND_GLOBAL = new EventOptions(true, true);

    private final boolean prepend;
    private final boolean global;

    private EventOptions(boolean prepend, boolean global) {
        this.prepend = prepend;
        this.global = global;
    }

    public static EventOptions defaults() {
        return DEFAULT;
    }

    public static EventOptions prepend() {
        return PREPEND;
    }

    public static EventOptions global() {
        return GLOBAL;
    }

    public static EventOptions prependGlobal() {
        return PREPEND_GLOBAL;
    }

    public boolean isPrepend() {
        return prepend;
    }

    public boolean isGlobal() {
        return global;
    }
}
