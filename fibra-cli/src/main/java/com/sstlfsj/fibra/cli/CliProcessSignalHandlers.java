package com.sstlfsj.fibra.cli;

import org.jline.utils.Signals;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 独立于 JLine 行编辑器注册进程级 INT/TERM 信号。 */
final class CliProcessSignalHandlers implements AutoCloseable {
    interface Registrar {
        Object register(String name, Runnable handler);

        void unregister(String name, Object previous);
    }

    private static final Registrar SYSTEM = new Registrar() {
        @Override
        public Object register(String name, Runnable handler) {
            return Signals.register(name, handler);
        }

        @Override
        public void unregister(String name, Object previous) {
            Signals.unregister(name, previous);
        }
    };

    private final Registrar registrar;
    private final List<Registration> registrations;
    private boolean closed;

    private CliProcessSignalHandlers(Registrar registrar, List<Registration> registrations) {
        this.registrar = registrar;
        this.registrations = registrations;
    }

    static CliProcessSignalHandlers install(CliProcessShutdown shutdown) {
        return install(shutdown, SYSTEM);
    }

    static CliProcessSignalHandlers install(CliProcessShutdown shutdown,
                                            Registrar registrar) {
        Objects.requireNonNull(shutdown, "shutdown");
        Objects.requireNonNull(registrar, "registrar");
        var registrations = new ArrayList<Registration>();
        registrations.add(register(registrar, "INT",
            () -> shutdown.interrupt(CliProcessShutdown.Signal.INT)));
        registrations.add(register(registrar, "TERM",
            () -> shutdown.interrupt(CliProcessShutdown.Signal.TERM)));
        return new CliProcessSignalHandlers(registrar, List.copyOf(registrations));
    }

    private static Registration register(Registrar registrar, String name, Runnable handler) {
        return new Registration(name, registrar.register(name, handler));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var index = registrations.size() - 1; index >= 0; index--) {
            var registration = registrations.get(index);
            registrar.unregister(registration.name(), registration.previous());
        }
    }

    private record Registration(String name, Object previous) {
    }
}
