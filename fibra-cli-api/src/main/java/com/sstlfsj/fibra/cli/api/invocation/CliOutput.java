package com.sstlfsj.fibra.cli.api.invocation;

public interface CliOutput {
    void stdout(String value);

    void stderr(String value);
}
