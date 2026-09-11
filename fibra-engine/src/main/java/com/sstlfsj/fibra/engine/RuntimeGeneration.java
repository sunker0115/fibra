package com.sstlfsj.fibra.engine;

import reactor.core.publisher.Mono;

/** 一代独占的运行时资源；调用方负责在 domain 关闭后释放，不参与发布决策。 */
public interface RuntimeGeneration {
    /** 所有者登记句柄后准备资源；失败仍由同一所有者调用 closeAsync。 */
    Mono<Void> prepareAsync();

    RuntimeGenerationSnapshot snapshot();

    PluginCatalog catalog();

    /** 幂等、可等待；重复订阅观察同一次关闭的完成或失败。 */
    Mono<Void> closeAsync();
}
