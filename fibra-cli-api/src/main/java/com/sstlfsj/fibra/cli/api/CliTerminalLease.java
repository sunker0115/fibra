package com.sstlfsj.fibra.cli.api;

import java.io.IOException;

public interface CliTerminalLease extends AutoCloseable {
    int read() throws IOException;

    void write(String value) throws IOException;

    void flush() throws IOException;

    @Override
    void close();
}
