package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource

/**
 * A launch whose process-group launcher started and could not exec the program (design D19 as
 * amended 2026-10-17, second, of fix-terminal-receipt-convergence): it writes the perl launcher's
 * refusal line where the builder sends the child's stderr — the file a round's stderr goes to, or
 * the merged output of a check — and hands back a {@link FakeProcess} that exited 127 with nothing
 * on stdout. The real launcher cannot be made to refuse on demand once the owner resolves the
 * executable and its interpreter chain first, and the residue it leaves (a {@code noexec} mount, a
 * race with the exec) has no portable fixture; what a streaming waiter does with the line is the
 * subject, and it is the same line either way. {@code LauncherRefusalSpec} in {@code :subprocess}
 * pins that {@link #line} is what the real macOS launcher writes and that the owner classifies it.
 */
final class RefusingLaunch implements ProcessLaunch {

    /** The perl launcher's refusal of {@code path} for {@code ENOENT}, as it writes it. */
    static String line(String path) {
        "gnomish-launch: cannot exec ${path}: No such file or directory\n"
    }

    @Override
    Process start(ProcessBuilder builder, StdinSource stdin) throws IOException {
        String refusal = line(builder.command().first())
        FakeProcess process = FakeProcess.running()
        if (builder.redirectErrorStream()) {
            process.writeStdout(refusal)
        } else if (builder.redirectError().file() != null) {
            builder.redirectError().file().append(refusal)
        } else {
            process.writeStderr(refusal)
        }
        process.exit(127)
        process
    }
}
