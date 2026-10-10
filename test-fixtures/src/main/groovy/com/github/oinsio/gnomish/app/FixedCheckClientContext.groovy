package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment

/**
 * A {@link CheckClientContext} of fixed values — what a spec hands a check client factory in place
 * of the host's own context (design D21 of supervise-daemon-loops-and-embed-dashboard). The run
 * context defaults to {@link CheckRunContext#none()} and the time equipment to a fresh virtual one.
 *
 * <p>Test fixture; never shipped. Implements FR23 of supervise-daemon-loops-and-embed-dashboard.
 */
final class FixedCheckClientContext implements CheckClientContext {

    final SecretsProvider secrets
    final Map<String, Object> subsection
    final CheckRunContext runContext
    final TimeEquipment timeEquipment

    FixedCheckClientContext(
    SecretsProvider secrets,
    Map<String, Object> subsection,
    CheckRunContext runContext = CheckRunContext.none(),
    TimeEquipment timeEquipment = VirtualTimeEquipment.create()) {
        this.secrets = secrets
        this.subsection = subsection
        this.runContext = runContext
        this.timeEquipment = timeEquipment
    }

    @Override
    SecretsProvider secrets() {
        secrets
    }

    @Override
    Map<String, Object> subsection() {
        subsection
    }

    @Override
    CheckRunContext runContext() {
        runContext
    }

    @Override
    TimeEquipment timeEquipment() {
        timeEquipment
    }
}
