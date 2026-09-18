package com.github.oinsio.gnomish.domain.engine.port.contract

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The scripted
 * {@link com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner} is
 * the first concrete implementation of the built-in half of
 * {@link CheckRunnerContract}: it produces every {@link Verdict} variant unchanged,
 * so no contract row is skipped. The real {@code files_exist} runner (add-manual-run
 * §4.5) later subclasses the SAME suite.
 *
 * <p>FR14 of add-manual-run: the scripted fake passes the extracted port-contract
 * suite unchanged (metric M2).
 */
class ScriptedBuiltinCheckRunnerContractSpec extends CheckRunnerContract {

    private static Verdict scriptedVerdict(VerdictVariant variant) {
        return switch (variant) {
                    case VerdictVariant.PASS -> new Verdict.Pass()
                    case VerdictVariant.FAIL_WITH_FINDINGS ->
                    new Verdict.Fail([
                        new Finding('a file is missing', 'src/Foo.java', null)
                    ])
                    case VerdictVariant.CANNOT_VERIFY ->
                    new Verdict.CannotVerify(UntrustedText.subprocess('check id unknown'), UntrustedText.subprocess(''))
                }
    }

    @Override
    protected Optional<Verdict> arrange(VerdictVariant variant) {
        def runner = ScriptedBuiltinCheckRunner.fixed(scriptedVerdict(variant))
        Optional.of(runner.run(new VerifyCheck.Builtin('files_exist', [:]), new FakeWorkspace()))
    }

    @Override
    protected String portName() {
        'BuiltinCheckRunner'
    }
}
