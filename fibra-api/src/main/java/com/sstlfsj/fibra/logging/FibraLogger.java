package com.sstlfsj.fibra.logging;

public interface FibraLogger {
    String name();

    void error(Object... arguments);

    void info(Object... arguments);

    void warn(Object... arguments);

    void debug(Object... arguments);
}
