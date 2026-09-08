package com.sstlfsj.fibra.engine;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ChangeSet {
    private final String id;
    private final List<ChangeParticipant> participants;
    private final Supplier<Mono<Void>> verifier;
    private final Supplier<Mono<Void>> publisher;

    private ChangeSet(Builder builder) {
        id = builder.id;
        participants = List.copyOf(builder.participants);
        verifier = builder.verifier;
        publisher = builder.publisher;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    List<ChangeParticipant> participants() {
        return participants;
    }

    Mono<Void> verify() {
        return Objects.requireNonNull(verifier.get(), "change verifier returned null");
    }

    Mono<Void> publish() {
        return Objects.requireNonNull(publisher.get(), "change publisher returned null");
    }

    public static final class Builder {
        private final String id;
        private final List<ChangeParticipant> participants = new ArrayList<>();
        private Supplier<Mono<Void>> verifier = Mono::empty;
        private Supplier<Mono<Void>> publisher = Mono::empty;

        private Builder(String id) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("change set id must not be blank");
            }
            this.id = id;
        }

        public Builder participant(ChangeParticipant participant) {
            participants.add(Objects.requireNonNull(participant, "participant"));
            return this;
        }

        public Builder verify(Supplier<Mono<Void>> value) {
            verifier = Objects.requireNonNull(value, "verifier");
            return this;
        }

        public Builder publish(Supplier<Mono<Void>> value) {
            publisher = Objects.requireNonNull(value, "publisher");
            return this;
        }

        public ChangeSet build() {
            var names = new java.util.HashSet<String>();
            for (var participant : participants) {
                if (participant.name() == null || participant.name().isBlank()) {
                    throw new IllegalArgumentException("participant name must not be blank");
                }
                if (!names.add(participant.name())) {
                    throw new IllegalArgumentException(
                        "duplicate participant " + participant.name());
                }
            }
            return new ChangeSet(this);
        }
    }
}
