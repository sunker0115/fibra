package com.sstlfsj.fibra.cli.api;

public interface CliOutput {
    void stdout(String value);

    void stderr(String value);
}
