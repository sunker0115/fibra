package com.sstlfsj.fibra.plugins.subprocess.local;

import java.io.InputStream;

/** One Windows managed range whose native handles have a single lifecycle owner. */
interface WindowsJobOwner {
    InputStream stdout();

    InputStream stderr();

    int waitForDirectExit() throws Exception;

    void waitForEmpty() throws Exception;

    void terminate() throws Exception;

    void close() throws Exception;
}
