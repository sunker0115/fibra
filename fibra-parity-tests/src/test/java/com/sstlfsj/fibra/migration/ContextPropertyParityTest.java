package com.sstlfsj.fibra.migration;

import com.sstlfsj.fibra.*;
import com.sstlfsj.fibra.runtime.FibraRuntime;

import org.junit.jupiter.api.AfterEach;
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

    private final Context context = FibraRuntime.create();

    @AfterEach
    void closeContext() {
        context.closeAsync().block();
    }

    @Test
    void typedAccessorIsEffectOwnedAndSupportsReadWrite() {
        var session = new Session();
        var handle = context.accessor(SESSION_ANSWER, new PropertyAccessor<>() {
            @Override
            public Integer get(Context caller, Session receiver) {
                return receiver.answer;
            }

            @Override
            public void set(Context caller, Session receiver, Integer value) {
                receiver.answer = value + 1;
            }
        });

        context.associate(session).set(SESSION_ANSWER, 41);
        assertEquals(42, context.associate(session).get(SESSION_ANSWER));

        handle.dispose().block();
        assertThrows(IllegalStateException.class,
            () -> context.associate(session).get(SESSION_ANSWER));
    }

    @Test
    void associatedAccessorResolvesServicesFromTheCallerFibra() {
        context.provide(ANSWER, session -> session.answer + 1);
        context.accessor(SESSION_ANSWER, PropertyAccessor.readOnly(
            (caller, session) -> caller.get(ANSWER).answer(session)));
        var associated = new AtomicReference<Associated<Session>>();
        var consumer = context.plugin(PluginDescriptor.<Void>builder("consumer")
            .require(ANSWER)
            .build(), (pluginContext, ignored) -> {
            associated.set(pluginContext.associate(new Session(41)));
            return Mono.empty();
        }, null);

        consumer.await().block();

        assertEquals(42, associated.get().get(SESSION_ANSWER));
    }

    @FunctionalInterface
    interface AnswerService {
        int answer(Session session);
    }

    static final class Session {
        private int answer;

        Session() {
        }

        Session(int answer) {
            this.answer = answer;
        }
    }
}
