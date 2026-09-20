package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.CancellationToken;
import com.sstlfsj.fibra.value.LiteralValue;

import java.util.Optional;
import java.util.concurrent.CancellationException;

public interface ContributionCodec<D, I, O> {
    int schemaVersion();

    D decodeDescriptor(LiteralValue descriptor);

    LiteralValue encodeInput(I input);

    I decodeInput(LiteralValue input);

    LiteralValue encodeOutput(O output);

    O decodeOutput(LiteralValue output);

    /** Returns the cooperative cancellation token carried by this invocation input. */
    default CancellationToken cancellationToken(I input) { return CancellationToken.never(); }

    /** Creates the public failure reported when the invocation token is cancelled. */
    default RuntimeException cancellationException() { return new CancellationException(); }

    /** Maps a recognized remote business failure; empty preserves the transport failure. */
    default Optional<RuntimeException> mapRemoteFailure(RemoteContributionFailure failure) {
        return Optional.empty();
    }
}
