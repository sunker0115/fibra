package com.sstlfsj.fibra;

import org.reactivestreams.Publisher;

@FunctionalInterface
public interface Plugin<C> {
    Publisher<? extends Disposable> apply(Context context, C config);
}
