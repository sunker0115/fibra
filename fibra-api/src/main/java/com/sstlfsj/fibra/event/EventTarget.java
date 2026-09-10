package com.sstlfsj.fibra.event;

import com.sstlfsj.fibra.Context;

import java.util.Objects;

@FunctionalInterface
public interface EventTarget {
    boolean accepts(Context listenerContext);

    static EventTarget context(Context context) {
        return new BoundContext(Objects.requireNonNull(context, "context"));
    }

    final class BoundContext implements EventTarget {
        private final Context context;

        private BoundContext(Context context) {
            this.context = context;
        }

        public Context context() {
            return context;
        }

        @Override
        public boolean accepts(Context listenerContext) {
            return listenerContext == context;
        }
    }
}
