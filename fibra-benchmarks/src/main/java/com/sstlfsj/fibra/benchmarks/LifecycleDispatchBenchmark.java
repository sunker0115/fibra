package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.Ticker;
import com.sstlfsj.fibra.event.EventKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 8)
@Fork(2)
public class LifecycleDispatchBenchmark {

    private static final EventKey<Ticker> EMPTY = EventKey.of("bench/empty", Ticker.class);

    private Context ctx;

    @Setup
    public void setup() {
        ctx = FibraRuntime.create();
    }

    @TearDown
    public void tearDown() {
        ctx.close();
    }

    @Benchmark
    public void roundTrip() {
        ctx.emit(EMPTY, Ticker::onTick);
    }
}
