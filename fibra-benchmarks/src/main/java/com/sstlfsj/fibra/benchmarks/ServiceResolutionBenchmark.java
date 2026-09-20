package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.ServiceRef;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

import static com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.ECHO;
import static com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.RESOLVE;

import com.sstlfsj.fibra.benchmarks.BenchmarkFixtures.Echo;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 8)
@Fork(2)
public class ServiceResolutionBenchmark {

    private static final int BATCH = 1000;

    private Context ctx;
    private FibraRuntime runtime;
    private ServiceRef<Echo> bound;

    @Setup
    public void setup() {
        runtime = FibraRuntime.create();
        ctx = runtime.rootScope().context();
        ctx.services().provide(ECHO, () -> 42);
        bound = ctx.services().reference(ECHO);
        ctx.events().on(RESOLVE, times -> {
            long acc = 0;
            for (int i = 0; i < times; i++) {
                acc += System.identityHashCode(ctx.services().require(ECHO));
            }
            return acc;
        });
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public Echo getOutside() {
        return ctx.services().require(ECHO);
    }

    @Benchmark
    public int invokeOutside() {
        return bound.invoke((ic, echo) -> echo.ping());
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public long resolveInside() {
        return ctx.events().bail(RESOLVE, l -> l.run(BATCH));
    }
}
