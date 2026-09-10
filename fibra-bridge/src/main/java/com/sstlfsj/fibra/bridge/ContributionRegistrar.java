package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.Disposable;
import reactor.core.publisher.Mono;

import java.util.List;

/** 运行代内由插件使用的只写 contribution 注册入口。 */
public interface ContributionRegistrar {
    <D, I, O> Mono<ContributionRegistration> register(
        Context owner, ContributionKind<D, I, O> kind,
        String providerInstanceId, String localName, D descriptor,
        ContributionHandler<I, O> handler);

    Mono<List<ContributionRegistration>> registerAll(
        Context owner, String providerInstanceId,
        List<ContributionBinding<?, ?, ?>> bindings, Disposable afterDrain);
}
