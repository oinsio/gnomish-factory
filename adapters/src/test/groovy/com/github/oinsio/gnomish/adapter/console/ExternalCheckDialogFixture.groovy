package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.app.console.DialogConsole
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration

/**
 * Shared arrangement for the two {@link InteractiveExternalCheckClient} specs —
 * the behavioral one and the contract one: a single sample check, a single
 * opaque workspace and the scripted-dialog poll they both drive, defined once
 * instead of copied into each spec.
 *
 * <p>Test helper for add-manual-run FR4/FR14; not production code.
 */
final class ExternalCheckDialogFixture {

    /** The sample external check both specs poll. */
    static VerifyCheck.External sampleCheck() {
        new VerifyCheck.External('ci-build', 'github', Duration.ofSeconds(30), Duration.ofMinutes(5),
                VerifyCheck.TimeoutClass.QUALITY)
    }

    /** An opaque workspace — the interactive adapter never inspects it. */
    static Workspace sampleWorkspace() {
        new Workspace() {}
    }

    /**
     * Polls {@link #sampleCheck} once through a dialog whose answers come from
     * {@code io}, so callers keep the fake for their {@code printed} assertions.
     */
    static PollStatus poll(ScriptedConsoleIO io) {
        def console = new DialogConsole(io, { json -> 'status' })
        new InteractiveExternalCheckClient(console).poll(sampleCheck(), sampleWorkspace())
    }
}
