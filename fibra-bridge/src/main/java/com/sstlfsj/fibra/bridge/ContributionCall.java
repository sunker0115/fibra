package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.Context;
import reactor.core.publisher.Mono;

/** 一次 contribution 调用持有的注册租约。 */
public interface ContributionCall<I, O> extends AutoCloseable {
    Mono<O> invoke(Context caller, I input);

    /**
     * 释放调用租约，并标记调用侧资源清理失败。
     *
     * <p>detail 必须是可跨生命周期边界保留的稳定文本。</p>
     */
    void failCleanup(String detail);

    @Override
    void close();
}
