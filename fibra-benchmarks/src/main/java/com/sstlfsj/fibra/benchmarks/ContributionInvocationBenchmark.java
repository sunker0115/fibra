package com.sstlfsj.fibra.benchmarks;

import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.bridge.ContributionAdmission;
import com.sstlfsj.fibra.bridge.ContributionDirectory;
import com.sstlfsj.fibra.bridge.ContributionId;
import com.sstlfsj.fibra.bridge.ContributionKind;
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
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 8)
@Fork(2)
public class ContributionInvocationBenchmark {
    private static final ContributionKind<String, Integer, Integer> KIND =
        ContributionKind.local("bench-operation", String.class,
            Integer.class, Integer.class);
    private static final ContributionId ID = new ContributionId("bench-provider", "add-one");

    private FibraRuntime runtime;
    private Context context;
    private ContributionDirectory directory;
    private ContributionAdmission admission;

    @Setup
    public void setup() {
        runtime = FibraRuntime.create();
        context = runtime.rootScope().context();
        directory = new ContributionDirectory();
        admission = directory.openAdmission(ID.providerInstanceId());
        admission.register(context, KIND, ID.localName(),
            "Add one", (invocation, input) -> Mono.just(input + 1)).block();
    }

    @TearDown
    public void tearDown() {
        admission.closeAdmission();
        admission.drainAsync().block();
        runtime.close();
        directory.close();
    }

    @Benchmark
    public Integer invokeLocalContribution() {
        return directory.current().routes().invoke(context, KIND, ID, 41).block();
    }
}
