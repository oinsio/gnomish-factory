package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.engine.port.contract.ExternalCheckClientContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration

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

    private static VerifyCheck.External sampleCheck() {
        new VerifyCheck.External('ci-build', Duration.ofSeconds(30), Duration.ofMinutes(5))
    }

    private static Workspace sampleWorkspace() {
        new Workspace() {}
    }

    @Override
    protected Optional<PollStatus> arrange(ExternalCheckClientContract.PollVariant variant) {
        if (variant == ExternalCheckClientContract.PollVariant.CANNOT_VERIFY) {
            return Optional.empty()
        }
        List<String> script = switch (variant) {
                    case ExternalCheckClientContract.PollVariant.PASS -> ['pass']
                    case ExternalCheckClientContract.PollVariant.RUNNING -> ['running']
                    case ExternalCheckClientContract.PollVariant.FAIL_WITH_FINDINGS ->
                    ['fail', 'CI check failed', '']
                }
        def io = new ScriptedConsoleIO(script)
        def console = new DialogConsole(io, { json -> 'status' })
        def client = new InteractiveExternalCheckClient(console)
        Optional.of(client.poll(sampleCheck(), sampleWorkspace()))
    }
}
