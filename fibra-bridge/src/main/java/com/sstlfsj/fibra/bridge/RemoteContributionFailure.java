package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Objects;

/** Immutable JSON-safe error details received from a remote contribution endpoint. */
public record RemoteContributionFailure(int code, String message, LiteralValue data) {
    public RemoteContributionFailure {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(data, "data");
    }
}
