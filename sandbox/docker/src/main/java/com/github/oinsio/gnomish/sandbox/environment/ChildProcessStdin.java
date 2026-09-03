package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.logtext.MdcAwareThread;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds fire-and-forget stdin to a process started by {@link
 * TaskExecutionEnvironment#exec} — shared by the host and container adapters so
 * the one stdin-delivery path (and its one mutation-coverage exception) lives in
 * a single place. The bytes are written on a virtual thread so a large prompt
 * cannot deadlock against the child's own stdout buffering and a stuck write
 * never blocks the round's timeout wait (design D18).
 *
 * <p>Implements FR24 of add-sandbox-core.
 */
final class ChildProcessStdin {

    private static final Logger log = LoggerFactory.getLogger(ChildProcessStdin.class);

    private ChildProcessStdin() {}

    /**
     * Writes {@code stdin} to {@code process}'s standard input on a virtual
     * thread, closing it after; a {@code null} stdin leaves the input empty.
     *
     * @param process the started child process; never null
     * @param stdin the UTF-8 content to deliver, or {@code null} for no input
     */
    static void feed(Process process, @Nullable String stdin) {
        if (stdin == null) {
            return;
        }
        byte[] bytes = stdin.getBytes(StandardCharsets.UTF_8);
        Thread.ofVirtual().start(MdcAwareThread.inheritingContext(() -> pump(process, bytes)));
    }

    /**
     * PIT M4 documented exception: {@code @DoNotMutate} — the {@code IOException}
     * catch is a genuine race (the process may exit and close its stdin pipe
     * before draining our bytes), not reliably reproducible in a unit test; the
     * happy path (stdin delivered to the child) is covered by the fake-agent
     * stdin-echo spec and the port contract's stdin scenario.
     */
    @DoNotMutate
    private static void pump(Process process, byte[] bytes) {
        try (OutputStream os = process.getOutputStream()) {
            os.write(bytes);
        } catch (IOException e) {
            log.debug("process closed stdin before consuming it", e);
        }
    }
}
