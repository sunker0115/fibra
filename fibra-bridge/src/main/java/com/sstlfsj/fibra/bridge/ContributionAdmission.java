package com.sstlfsj.fibra.bridge;

import reactor.core.publisher.Mono;

/** 单个 execution unit 独占的 contribution 发布阶段门。 */
public interface ContributionAdmission extends ContributionRegistrar {
    /** 同步且幂等地封闭准入并撤销该 unit 已发布的全部 route。 */
    void closeAdmission();

    /** 封闭准入并异步等待已经取得的调用租约，不释放 handler 或插件资源。 */
    Mono<Void> drainAsync();
}
