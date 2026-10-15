package com.sstlfsj.fibra.engine.publication;

import com.sstlfsj.fibra.bridge.ContributionCodec;
import com.sstlfsj.fibra.bridge.ContributionHandle;
import com.sstlfsj.fibra.bridge.ContributionKind;
import com.sstlfsj.fibra.bridge.ContributionKindRegistry;
import com.sstlfsj.fibra.value.LiteralValue;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** transport-neutral 远端调用唯一入口；所有准入事实仍由 PublishedRuntime 判定。 */
public final class RemoteContributionInvoker {
    private final ContributionKindRegistry kinds;
    private final PublishedRuntime published;

    public RemoteContributionInvoker(ContributionKindRegistry kinds,
                                     PublishedRuntime published) {
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.published = Objects.requireNonNull(published, "published");
    }

    public Mono<LiteralValue> invoke(String kindName, ContributionHandle handle,
                                     LiteralValue input) {
        Objects.requireNonNull(kindName, "kindName");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(input, "input");
        return Mono.defer(() -> kinds.find(kindName)
            .map(kind -> invokeResolved(kind, handle, input))
            .orElseGet(() -> Mono.error(failure(
                RemoteContributionInvocationException.Code.UNKNOWN_KIND,
                "unknown contribution kind " + kindName, null))));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Mono<LiteralValue> invokeResolved(ContributionKind kind,
                                              ContributionHandle handle,
                                              LiteralValue input) {
        var optional = kind.codec();
        if (optional.isEmpty()) {
            return Mono.error(failure(
                RemoteContributionInvocationException.Code.KIND_NOT_REMOTE,
                "contribution kind is not remote-capable: " + kind.name(), null));
        }
        var codec = (ContributionCodec) optional.orElseThrow();
        final Object decoded;
        try {
            decoded = codec.decodeInput(input);
        } catch (RuntimeException error) {
            return Mono.error(failure(
                RemoteContributionInvocationException.Code.INPUT_CODEC,
                "cannot decode input for contribution kind " + kind.name(), error));
        }
        return ((Mono<?>) published.invoke(handle, kind, decoded))
            .flatMap(output -> Mono.fromCallable(() -> {
                try {
                    return (LiteralValue) codec.encodeOutput(output);
                } catch (RuntimeException error) {
                    throw failure(
                        RemoteContributionInvocationException.Code.OUTPUT_CODEC,
                        "cannot encode output for contribution kind " + kind.name(),
                        error);
                }
            }));
    }

    private static RemoteContributionInvocationException failure(
        RemoteContributionInvocationException.Code code, String message,
        Throwable cause) {
        return new RemoteContributionInvocationException(code, message, cause);
    }
}
