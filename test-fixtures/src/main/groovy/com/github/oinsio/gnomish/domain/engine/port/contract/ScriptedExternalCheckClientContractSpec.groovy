package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration

/**
 * The scripted
 * {@link com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient} is
 * the first concrete implementation of {@link ExternalCheckClientContract}: it
 * produces every {@link PollStatus} variant unchanged, so no contract row is skipped.
 * The interactive external client (add-manual-run §5.5) later subclasses the SAME
 * suite.
 *
 * <p>FR14 of add-manual-run: the scripted fake passes the extracted port-contract
 * suite unchanged (metric M2).
 */
class ScriptedExternalCheckClientContractSpec extends ExternalCheckClientContract {

    @Override
    protected Optional<PollStatus> arrange(PollVariant variant) {
        def client = new ScriptedExternalCheckClient([scriptedStatus(variant)])
        def check = new VerifyCheck.External('ci', 'scripted', Duration.ofSeconds(1), Duration.ofSeconds(10), VerifyCheck.TimeoutClass.QUALITY)
        Optional.of(client.poll(check, new FakeWorkspace()))
    }
}
