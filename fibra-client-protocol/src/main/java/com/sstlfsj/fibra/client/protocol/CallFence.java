package com.sstlfsj.fibra.client.protocol;

import java.util.Objects;

/** 精确绑定一次 contribution 调用或回复的已发布 view。 */
public record CallFence(SessionFence session, long expectedViewRevision, String registrationIdentity) {
    public CallFence {
        session = Objects.requireNonNull(session, "session");
        if (expectedViewRevision < 1) {
            throw new IllegalArgumentException("expectedViewRevision must be positive");
        }
        registrationIdentity = Objects.requireNonNull(registrationIdentity, "registrationIdentity");
        if (registrationIdentity.isBlank()) {
            throw new IllegalArgumentException("registrationIdentity must not be blank");
        }
    }
}
