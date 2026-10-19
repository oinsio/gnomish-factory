package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ChildOutput
import java.nio.charset.StandardCharsets

/**
 * The agent specs' stdout for an {@code ExecHandle} double, minted as a {@link ChildOutput} from the
 * one {@code Process} double (design D6 of make-pipe-reads-non-parking): a child that wrote its
 * answer and exited, or a live child that never writes, whose output ends only when the round stops
 * it — or when the spec's cleanup ends the child, so a mutant that never stops it frees the thread.
 */
final class CannedOutputs {

    private CannedOutputs() {
    }

    /** The stdout of a child that wrote {@code text} and exited; the drain reads it, then ends. */
    static ChildOutput written(String text) {
        def child = FakeProcess.running()
        child.writeStdout(text)
        child.exit(0)
        ChildOutput.stdout(child)
    }

    /** {@link #written(String)} over a resource's UTF-8 content. */
    static ChildOutput written(InputStream resource) {
        written(new String(resource.withCloseable {
            it.readAllBytes()
        }, StandardCharsets.UTF_8))
    }

    /** The stdout of {@code live}, a child that writes nothing: it never ends while the child lives. */
    static ChildOutput nonEnding(FakeProcess live) {
        ChildOutput.stdout(live)
    }
}
