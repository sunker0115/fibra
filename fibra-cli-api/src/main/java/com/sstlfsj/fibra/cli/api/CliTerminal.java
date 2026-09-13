package com.sstlfsj.fibra.cli.api;

public interface CliTerminal {
    boolean interactive();

    CliTerminalLease acquire();
}
