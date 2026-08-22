package com.sstlfsj.fibra.event;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Fibra;
import com.sstlfsj.fibra.FibraState;
import com.sstlfsj.fibra.ServiceKey;
import org.reactivestreams.Publisher;

public final class CoreEvents {
    public static final EventKey<PluginListener> PLUGIN =
        EventKey.of("internal/plugin", PluginListener.class);
    public static final EventKey<StatusListener> STATUS =
        EventKey.of("internal/status", StatusListener.class);
    public static final EventKey<ServiceListener> SERVICE =
        EventKey.of("internal/service", ServiceListener.class);
    public static final EventKey<UpdateListener> UPDATE =
        EventKey.of("internal/update", UpdateListener.class);
    public static final EventKey<GetListener> GET =
        EventKey.of("internal/get", GetListener.class);
    public static final EventKey<SetListener> SET =
        EventKey.of("internal/set", SetListener.class);
    public static final EventKey<ListenerListener> LISTENER =
        EventKey.of("internal/listener", ListenerListener.class);
    public static final EventKey<DispatchListener> DISPATCH =
        EventKey.of("internal/dispatch", DispatchListener.class);

    private CoreEvents() {
    }

    @FunctionalInterface
    public interface PluginListener {
        void onPlugin(Fibra fibra);
    }

    @FunctionalInterface
    public interface StatusListener {
        void onStatus(Fibra fibra, FibraState previous);
    }

    @FunctionalInterface
    public interface ServiceListener {
        void onService(ServiceKey<?> key, Object value);
    }

    @FunctionalInterface
    public interface UpdateListener {
        Publisher<Fibra> onUpdate(Fibra fibra, Object config, boolean noSave,
                                  Next<Publisher<Fibra>> next);
    }

    @FunctionalInterface
    public interface GetListener {
        Object onGet(Context caller, ServiceKey<?> key, Next<Object> next);
    }

    @FunctionalInterface
    public interface SetListener {
        boolean onSet(Context caller, ServiceKey<?> key, Object value, Next<Boolean> next);
    }

    @FunctionalInterface
    public interface ListenerListener {
        void onListener(Context owner, EventKey<?> key, Object listener, boolean added);
    }

    @FunctionalInterface
    public interface DispatchListener {
        void onDispatch(String mode, EventKey<?> key, EventTarget target);
    }
}
