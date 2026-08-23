package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.event.Next;

/** 基准共用的服务与事件契约常量。 */
public final class BenchmarkFixtures {

    public interface Echo {
        int ping();
    }

    public static final ServiceKey<Echo> ECHO = ServiceKey.of("bench/echo", Echo.class);

    public interface Ticker {
        void onTick();
    }

    public static final EventKey<Ticker> TICK = EventKey.of("bench/tick", Ticker.class);

    public interface Step {
        Integer step(Integer in, Next<Integer> next);
    }

    public static final EventKey<Step> WF = EventKey.of("bench/wf", Step.class);

    public interface ResolveLoop {
        long run(int times);
    }

    public static final EventKey<ResolveLoop> RESOLVE = EventKey.of("bench/resolve", ResolveLoop.class);

    private BenchmarkFixtures() {
    }
}
