package com.sstlfsj.fibra.engine;

import reactor.core.publisher.Mono;

public interface ChangeParticipant {
    String name();

    Mono<PreparedChange> prepare();
}
