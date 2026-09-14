package com.sstlfsj.fibra.engine;

import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionServices;
import com.sstlfsj.fibra.config.DesiredInputEntry;
import com.sstlfsj.fibra.config.DesiredInputGraph;
import com.sstlfsj.fibra.config.InMemoryDesiredStateRepository;
import com.sstlfsj.fibra.value.LiteralValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRuntimeBackpressureTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ContributionKind<Descriptor, String, String> COMMAND =
        ContributionKind.local("command", Descriptor.class, String.class, String.class);

    @Test
    @Timeout(20)
    void zeroDemandRetainsOnlyTheFinalFactAndReleasesRetiredDescriptors() throws Exception {
        var definition = PluginDefinition.builder("command", String.class, () -> (context, marker) ->
            context.services().require(ContributionServices.REGISTRAR)
                .register(context, COMMAND, "command", "run", new Descriptor(marker),
                    (invocation, input) -> Mono.just(marker)).then())
            .require(ContributionServices.REGISTRAR).build();
        try (var engine = FibraEngine.builder(new InMemoryDesiredStateRepository(graph("initial")))
            .catalog(PluginCatalog.of(new PluginCatalogEntry<>(definition, value -> (String) value))).build()) {
            engine.start().block(TIMEOUT);
            var deliveredRevision = new AtomicReference<String>();
            var delivered = new CountDownLatch(1);
            var subscriber = new BaseSubscriber<PublishedView>() {
                @Override protected void hookOnSubscribe(Subscription subscription) { }
                @Override protected void hookOnNext(PublishedView view) {
                    deliveredRevision.set(view.viewRevision());
                    delivered.countDown();
                }
            };
            engine.published().views().subscribe(subscriber);
            try {
                var retired = publishChanges(engine);
                var finalRevision = engine.published().current().viewRevision();
                var active = new WeakReference<>(engine.published().current()
                    .contributions().entries().getFirst().descriptor());
                assertTrue(Long.parseLong(finalRevision) > 256);
                assertNull(deliveredRevision.get(), "zero demand must not receive facts");
                assertAll(() -> awaitCollected(retired),
                    () -> {
                        subscriber.request(1);
                        assertTrue(delivered.await(5, TimeUnit.SECONDS));
                        assertEquals(finalRevision, deliveredRevision.get(),
                            "resumed demand must receive the final fact, not an old queued fact");
                    },
                    () -> assertNotNull(active.get(), "the current descriptor is the live control"));
                Reference.reachabilityFence(engine);
                Reference.reachabilityFence(subscriber);
            } finally {
                subscriber.dispose();
            }
        }
    }

    private static List<WeakReference<?>> publishChanges(FibraEngine engine) {
        var retired = new ArrayList<WeakReference<?>>();
        for (var round = 0; round < 300; round++) {
            var current = engine.published().current();
            retired.add(new WeakReference<>(current.contributions().entries().getFirst().descriptor()));
            engine.submit(new ReplaceDesiredGraph(current.viewRevision(),
                current.engine().desiredSource().revision(), graph("marker-" + round))).block(TIMEOUT);
        }
        return retired;
    }

    private static void awaitCollected(List<WeakReference<?>> retired) throws InterruptedException {
        for (var attempt = 0; attempt < 60; attempt++) {
            System.gc();
            if (retired.stream().allMatch(reference -> reference.get() == null)) return;
            Thread.sleep(25);
        }
        assertEquals(0, retired.stream().filter(reference -> reference.get() != null).count(),
            "an engine notification queue retains retired descriptors while demand is zero");
    }

    private static DesiredInputGraph graph(String marker) {
        return new DesiredInputGraph(List.of(DesiredInputEntry.builder("command", "command")
            .config(LiteralValue.of(marker)).build()));
    }

    private record Descriptor(String marker) { }
}
