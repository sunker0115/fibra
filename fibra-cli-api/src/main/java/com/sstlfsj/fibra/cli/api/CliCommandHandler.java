package com.sstlfsj.fibra.cli.api;

@FunctionalInterface
public interface CliCommandHandler {
    CliCommandResult invoke(CliCommandRequest request) throws Exception;
}
