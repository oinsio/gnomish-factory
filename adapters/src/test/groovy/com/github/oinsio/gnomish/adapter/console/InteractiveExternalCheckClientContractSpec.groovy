package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.contract.ExternalCheckClientContract

/**
 * {@link InteractiveExternalCheckClient} against the abstract {@link
 * ExternalCheckClientContract}: {@code Pass}, {@code Fail} and {@code
 * Running} are reachable through real dialog answers. {@code CannotVerify}
 * is NOT reachable — the adapter's prompt only accepts {@code pass} /
 * {@code fail} / {@code running} (see {@link InteractiveExternalCheckClient
 * #ACCEPTED_ANSWERS}); there is no dialog path that yields {@link
 * PollStatus.CannotVerify}, so that row declares itself unproducible rather
 * than fabricate an undocumented answer to force it green.
 *
 * <p>Implements FR14, M2 of add-manual-run.
 */
class InteractiveExternalCheckClientContractSpec extends ExternalCheckClientContract {

    @Override
    protected Optional<PollStatus> arrange(PollVariant variant) {
        List<String> script = switch (variant) {
                    case PollVariant.PASS -> ['pass']
                    case PollVariant.RUNNING -> ['running']
                    case PollVariant.FAIL_WITH_FINDINGS -> ['fail', 'CI check failed', '']
                    case PollVariant.CANNOT_VERIFY -> null
                }
        if (script == null) {
            return Optional.empty()
        }
        Optional.of(ExternalCheckDialogFixture.poll(new ScriptedConsoleIO(script)))
    }
}
