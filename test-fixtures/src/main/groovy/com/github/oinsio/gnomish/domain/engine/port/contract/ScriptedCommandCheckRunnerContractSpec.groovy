package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * The scripted
 * {@link com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner} is
 * the first concrete implementation of the command half of
 * {@link CheckRunnerContract}: it produces every {@link Verdict} variant unchanged,
 * so no contract row is skipped. The real command runner (add-manual-run §4.5) later
 * subclasses the SAME suite.
 *
 * <p>FR14 of add-manual-run: the scripted fake passes the extracted port-contract
 * suite unchanged (metric M2).
 */
class ScriptedCommandCheckRunnerContractSpec extends CheckRunnerContract {

    private static Verdict scriptedVerdict(CheckRunnerContract.VerdictVariant variant) {
        switch (variant) {
                    case CheckRunnerContract.VerdictVariant.PASS -> new Verdict.Pass()
                    case CheckRunnerContract.VerdictVariant.FAIL_WITH_FINDINGS ->
                    new Verdict.Fail([
                        new Finding('the test suite is red', null, 'exit 1')
                    ])
                    case CheckRunnerContract.VerdictVariant.CANNOT_VERIFY ->
                    new Verdict.CannotVerify('binary not found', '')
                }
    }

    @Override
    protected Optional<Verdict> arrange(CheckRunnerContract.VerdictVariant variant) {
        def runner = ScriptedCommandCheckRunner.fixed(scriptedVerdict(variant))
        Optional.of(runner.run(new VerifyCheck.Command('./gradlew test'), new FakeWorkspace()))
    }

    @Override
    protected String portName() {
        'CommandCheckRunner'
    }
}
