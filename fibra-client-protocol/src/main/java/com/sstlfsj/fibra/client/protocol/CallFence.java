package com.sstlfsj.fibra.client.protocol;

import java.util.Objects;

/** 精确绑定一次 contribution 调用或回复的已发布 view。 */
public record CallFence(SessionFence session, String expectedViewRevision, long registrationIdentity) {
    public CallFence {
        session = Objects.requireNonNull(session, "session");
        expectedViewRevision = Objects.requireNonNull(expectedViewRevision, "expectedViewRevision");
        if (expectedViewRevision.isBlank()) {
            throw new IllegalArgumentException("expectedViewRevision must not be blank");
        }
        if (registrationIdentity < 1) {
            throw new IllegalArgumentException("registrationIdentity must be positive");
        }
    }
}
