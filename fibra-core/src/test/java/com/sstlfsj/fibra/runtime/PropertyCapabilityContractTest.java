package com.sstlfsj.fibra.runtime;

import com.sstlfsj.fibra.PropertyAccessor;
import com.sstlfsj.fibra.PropertyKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropertyCapabilityContractTest {
    private static final PropertyKey<Session, Integer> ANSWER =
        PropertyKey.of("session.answer", Session.class, Integer.class);

    @Test
    void typedAccessorIsScopeOwnedAndSupportsReadWrite() {
        try (var runtime = FibraRuntime.create()) {
            var owner = runtime.rootScope().openChild("owner");
            var caller = runtime.rootScope().context();
            owner.context().properties().register(ANSWER, new PropertyAccessor<>() {
                @Override
                public Integer get(com.sstlfsj.fibra.Context context, Session receiver) {
                    return receiver.answer;
                }

                @Override
                public void set(com.sstlfsj.fibra.Context context, Session receiver,
                                Integer value) {
                    receiver.answer = value + 1;
                }
            });
            var associated = caller.properties().associate(new Session());

            associated.set(ANSWER, 41);
            assertEquals(42, associated.get(ANSWER));

            owner.close();
            assertThrows(IllegalStateException.class, () -> associated.get(ANSWER));
        }
    }

    @Test
    void associatedAccessorUsesTheCallerContext() {
        try (var runtime = FibraRuntime.create()) {
            var root = runtime.rootScope().context();
            root.properties().register(ANSWER, PropertyAccessor.readOnly(
                (caller, session) -> (Integer) caller.metadata("offset")
                    + session.answer));

            var associated = root.withMetadata("offset", 1)
                .properties().associate(new Session(41));

            assertEquals(42, associated.get(ANSWER));
        }
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
