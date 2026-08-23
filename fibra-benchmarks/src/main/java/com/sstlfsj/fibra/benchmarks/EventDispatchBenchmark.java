package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.Ticker;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

import static com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.TICK;
import static com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.WF;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 8)
@Fork(2)
public class EventDispatchBenchmark {

    @Param({"1", "8", "64"})
    private int hooks;

    private Context ctx;
    private long counter;

    @Setup
    public void setup() {
        ctx = FibraRuntime.create();
        for (int i = 0; i < hooks; i++) {
            ctx.on(TICK, () -> counter++);
            ctx.on(WF, (in, next) -> next.call() + 1);
        }
    }

    @TearDown
    public void tearDown() {
        ctx.close();
    }

    @Benchmark
    public long emit() {
        counter = 0;
        ctx.emit(TICK, Ticker::onTick);
        return counter;
    }

    @Benchmark
    public int waterfall() {
        return ctx.waterfall(WF, (l, next) -> l.step(0, next), () -> 0);
    }
}
