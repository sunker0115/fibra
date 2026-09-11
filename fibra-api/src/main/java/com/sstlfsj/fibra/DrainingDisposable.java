package com.sstlfsj.fibra;

import reactor.core.publisher.Mono;

/** 先关闭准入并排空已接受工作，再由 dispose 释放资源的受管资源。 */
public interface DrainingDisposable extends Disposable {
    /** 不释放实现资源；重复调用必须共享同一次排空的结果。 */
    Mono<Void> drain();
}
