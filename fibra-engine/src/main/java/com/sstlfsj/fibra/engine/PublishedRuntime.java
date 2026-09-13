package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 托管宿主读取事实和调用能力的唯一稳定入口。 */
public interface PublishedRuntime {
    PublishedView current();

    Flux<PublishedView> views();

    <D, I, O> Mono<O> invoke(String expectedViewRevision,
                             long expectedRegistrationIdentity,
                             ContributionKind<D, I, O> kind,
                             ContributionId id, I input);
}
