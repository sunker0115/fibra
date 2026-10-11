package com.sstlfsj.fibra.engine.runtime;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Runtime-owned query of static definition declarations, without executing target
 * plugin metadata, constructors or factories.
 *
 * <p>Creating a query is inert bookkeeping: it performs no I/O and acquires no
 * resources. The caller registers the query before reading; the query registers
 * each resource with its owner before any further operation can fail. Creation,
 * reading and closing must not introduce a second resource owner.
 *
 * <p>Read and close each advance at most once and cache their actual terminal
 * result for repeated subscriptions. Closing after reading has started waits for
 * the read's actual termination and does not cancel it. Subscription cancellation
 * must not be treated as successful read termination or resource closure.
 *
 * <p>Failed cleanup leaves the still-open resources reachable through the query
 * and the driver's index of that same owner. Successful cleanup releases resource
 * and original exception references. Closing before reading ends the inert query
 * without acquiring resources and rejects subsequent reads.
 */
public interface RuntimeDefinitionQuery {
    /** Reads one declaration list; an empty list is a valid facet without an entry. */
    Mono<List<String>> readAsync();

    /** Closes resources after the actual read termination and reports cleanup failure. */
    Mono<Void> closeAsync();
}
