package com.github.oinsio.gnomish.domain.engine.port

import spock.lang.Specification

/**
 * FR21 of add-sandbox-core: the no-precondition {@link AttemptDelivery} for assemblies whose
 * external checks have no push trigger to wait for (in-place mode, the interactive client)
 * reports {@code Delivered} for any workspace — never null, never {@code Undeliverable} — so
 * the engine's poll loop starts unconditionally there.
 */
class AttemptDeliverySpec extends Specification {

    // FR21: assumedDelivered() always answers Delivered — the poll loop may start.
    def "assumedDelivered reports Delivered for any workspace"() {
        given:
        def anyWorkspace = new Workspace() {}

        expect:
        AttemptDelivery.assumedDelivered().ensureDelivered(anyWorkspace) == new AttemptDelivery.Outcome.Delivered()
    }
}
