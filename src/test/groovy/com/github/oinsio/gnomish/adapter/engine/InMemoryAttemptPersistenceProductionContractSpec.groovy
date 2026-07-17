package com.github.oinsio.gnomish.adapter.engine

import com.github.oinsio.gnomish.domain.engine.port.contract.AttemptPersistenceContract

/**
 * The production {@link InMemoryAttemptPersistence} (§7.10, main source set) is a
 * second concrete implementation of {@link AttemptPersistenceContract}, subclassing
 * the SAME suite the test fake ({@code InMemoryAttemptPersistenceContractSpec})
 * passes: it appends each persist call to its {@code entries()} snapshot, in order,
 * so no contract row is skipped.
 *
 * <p>Implements D10, M2 of add-manual-run.
 */
class InMemoryAttemptPersistenceProductionContractSpec extends AttemptPersistenceContract {

    @Override
    protected Optional<?> arrange() {
        Optional.of(new InMemoryAttemptPersistence())
    }

    @Override
    protected List<AttemptPersistenceContract.PersistedEntry> retained(Object adapter) {
        ((InMemoryAttemptPersistence) adapter).entries().collect {
            new AttemptPersistenceContract.PersistedEntry(it.taskId(), it.state(), it.trace())
        }
    }
}
