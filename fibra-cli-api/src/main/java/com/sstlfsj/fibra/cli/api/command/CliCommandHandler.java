package com.sstlfsj.fibra.cli.api.command;

@FunctionalInterface
public interface CliCommandHandler {
    CliCommandResult invoke(CliCommandRequest request) throws Exception;
}
