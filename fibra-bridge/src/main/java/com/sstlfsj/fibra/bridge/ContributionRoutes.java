package com.sstlfsj.fibra.bridge;

import com.sstlfsj.fibra.Context;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

/** 某个目录 revision 冻结的 contribution 成员集合。 */
public final class ContributionRoutes {
    private final ContributionDirectory directory;
    private final Map<ContributionId, ContributionDirectory.Entry<?, ?, ?>> entries;

    ContributionRoutes(ContributionDirectory directory,
                       Map<ContributionId, ContributionDirectory.Entry<?, ?, ?>> entries) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.entries = Map.copyOf(entries);
    }

    public <D, I, O> ContributionCall<I, O> acquire(
        ContributionKind<D, I, O> kind, ContributionId id) {
        return directory.acquire(entries, kind, id);
    }

    public <D, I, O> Mono<O> invoke(Context caller, ContributionKind<D, I, O> kind,
                                    ContributionId id, I input) {
        return Mono.using(() -> acquire(kind, id), call -> call.invoke(caller, input),
            ContributionCall::close, true);
    }
}
