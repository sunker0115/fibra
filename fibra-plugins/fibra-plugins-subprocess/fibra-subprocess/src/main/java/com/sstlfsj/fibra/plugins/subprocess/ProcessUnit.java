package com.sstlfsj.fibra.plugins.subprocess;

import com.sstlfsj.fibra.DrainingDisposable;
import reactor.core.publisher.Mono;

public interface ProcessUnit extends DrainingDisposable {
    long pid();

    Mono<SubprocessOutcome> done();

    void terminate();

    Mono<Void> waitForExit();
}
