package com.sstlfsj.fibra.internal;

import com.sstlfsj.fibra.logging.FibraLogger;
import com.sstlfsj.fibra.logging.LogLevel;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

final class DefaultLoggerService {
    FibraLogger logger(DefaultContext context, String name) {
        return new DefaultFibraLogger(this, name);
    }

    void publish(String name, LogLevel level, Object[] arguments) {
        var logger = LoggerFactory.getLogger(name);
        var text = arguments.length == 0 ? "" : String.valueOf(arguments[0]);
        var rest = Arrays.stream(arguments).skip(1).toArray();
        switch (level) {
            case ERROR -> logger.error(text, rest);
            case INFO -> logger.info(text, rest);
            case WARN -> logger.warn(text, rest);
            case DEBUG -> logger.debug(text, rest);
        }
    }
}
