package com.sstlfsj.fibra.cli.api.terminal;

public interface CliTerminal {
    boolean interactive();

    CliTerminalLease acquire();
}
