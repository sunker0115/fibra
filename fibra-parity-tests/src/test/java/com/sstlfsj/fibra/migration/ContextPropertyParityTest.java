package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.Associated;
import com.sstlfsj.fibra.Context;
import com.sstlfsj.fibra.PluginDefinition;
import com.sstlfsj.fibra.PropertyAccessor;
import com.sstlfsj.fibra.PropertyKey;
import com.sstlfsj.fibra.ServiceKey;
import com.sstlfsj.fibra.runtime.FibraRuntime;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextPropertyParityTest {
    private static final ServiceKey<AnswerService> ANSWER =
        ServiceKey.of("answer", AnswerService.class);
    private static final PropertyKey<Session, Integer> SESSION_ANSWER =
        PropertyKey.of("session.answer", Session.class, Integer.class);

    @Test
    void typedAccessorIsEffectOwnedAndSupportsReadWrite() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            var owner = runtime.rootScope().openChild("property-owner");
            owner.context().properties().register(SESSION_ANSWER, new PropertyAccessor<>() {
                @Override
                public Integer get(Context caller, Session receiver) {
                    return receiver.answer;
                }

                @Override
                public void set(Context caller, Session receiver, Integer value) {
                    receiver.answer = value + 1;
                }
            });
            var associated = context.properties().associate(new Session());

            associated.set(SESSION_ANSWER, 41);
            assertEquals(42, associated.get(SESSION_ANSWER));
            owner.close();

            assertThrows(IllegalStateException.class,
                () -> associated.get(SESSION_ANSWER));
        }
    }

    @Test
    void associatedAccessorResolvesServicesFromTheCallerFibra() {
        try (var runtime = FibraRuntime.create()) {
            var context = runtime.rootScope().context();
            context.services().provide(ANSWER, session -> session.answer + 1);
            context.properties().register(SESSION_ANSWER, PropertyAccessor.readOnly(
                (caller, session) -> caller.services().require(ANSWER).answer(session)));
            var associated = new AtomicReference<Associated<Session>>();
            var definition = PluginDefinition.builder("consumer", Void.class,
                    () -> (pluginContext, ignored) -> {
                        associated.set(pluginContext.properties()
                            .associate(new Session(41)));
                        return Mono.empty();
                    })
                .require(ANSWER)
                .build();

            context.plugins().mount("consumer", definition, null).settled().block();

            assertEquals(42, associated.get().get(SESSION_ANSWER));
        }
    }

    @FunctionalInterface
    private interface AnswerService {
        int answer(Session session);
    }

    private static final class Session {
        private int answer;

        private Session() {
        }

        private Session(int answer) {
            this.answer = answer;
        }
    }
}
