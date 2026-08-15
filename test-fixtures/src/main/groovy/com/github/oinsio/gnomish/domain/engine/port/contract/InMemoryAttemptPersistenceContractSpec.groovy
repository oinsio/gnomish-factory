package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence

/**
 * The in-memory
 * {@link com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence} is
 * the first concrete implementation of {@link AttemptPersistenceContract}: it appends
 * each persist call to its public {@code entries} list in order, so no contract row is
 * skipped. The production in-memory persistence (§7.10) later subclasses the SAME suite
 * through the observation hook.
 *
 * <p>FR14 of add-manual-run: the in-memory fake passes the extracted port-contract
 * suite unchanged (metric M2).
 */
class InMemoryAttemptPersistenceContractSpec extends AttemptPersistenceContract {

    @Override
    protected Optional<?> arrange() {
        Optional.of(new InMemoryAttemptPersistence())
    }

    @Override
    protected List<AttemptPersistenceContract.PersistedEntry> retained(Object adapter) {
        ((InMemoryAttemptPersistence) adapter).entries.collect {
            new AttemptPersistenceContract.PersistedEntry(it.taskId, it.state, it.trace)
        }
    }
}
