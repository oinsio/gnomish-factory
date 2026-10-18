package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.StderrPolicy;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The round scratch directory (design D18 of fix-terminal-receipt-convergence, as amended
 * 2026-10-16): the one host directory an agent round writes outside the task branch, on the host
 * as for a box round. It holds the CLI's stderr file — the process writes it directly, in a box
 * through the host-side {@code docker exec} client's stderr — so no pipe is left that nothing
 * reads, and no thread drains a stream nobody needs live.
 *
 * <p>Opened before the round's {@code exec} and closed on every exit path, so the directory
 * leaves with the round however the round ended: a normal exit, a {@code roundTimeout} kill, an
 * interrupt, a launch that failed, an exception anywhere between. The stderr's tail is read once,
 * by the path that ended the round and before the close (design D18 as amended 2026-10-17): a
 * round that did not succeed puts {@link #tailForFailure} into its failure's detail, which the
 * operator reads at WARN and in the escalation; a successful round writes it as one DEBUG line
 * through {@link #logTailOnSuccess}. {@link #close} reads nothing, so one fact has one place. The
 * tail is read as agent text ({@link UntrustedText#agent}), minted at the read: the CLI writes it,
 * and what it quotes may come from the model.
 *
 * <p>The decision transport's own host directory ({@link DecisionFileTransport}) is not this
 * directory: in container mode the decision file lives in the task branch, which is the envelope
 * and no place for a host diagnostic.
 *
 * <p>Implements FR20 of fix-terminal-receipt-convergence.
 */
final class RoundScratchDirectory implements AutoCloseable {

    /** The JVM's temp directory: where every production round's directory is created. */
    static final Path HOST_TEMP = Path.of(System.getProperty("java.io.tmpdir"));

    /**
     * How much of the stderr's end is read: the bound the command check keeps of its output tail
     * ({@code TailLines}), since an error is written last; the log exit narrows it further.
     */
    static final int STDERR_TAIL_BYTES = 10 * 1024;

    private static final Logger log = LoggerFactory.getLogger(RoundScratchDirectory.class);

    private static final String PREFIX = "gnomish-round-";

    private static final String STDERR_FILE = "stderr";

    private final Path directory;

    private RoundScratchDirectory(Path directory) {
        this.directory = directory;
    }

    /**
     * Creates a fresh round directory under {@code parent}.
     *
     * @param parent the host directory to create it in; must exist and be writable
     * @return the open directory; never null
     * @throws UncheckedIOException if it cannot be created — the round cannot start
     */
    static RoundScratchDirectory open(Path parent) {
        try {
            return new RoundScratchDirectory(Files.createTempDirectory(parent, PREFIX));
        } catch (IOException e) {
            throw new UncheckedIOException("could not create the round scratch directory under " + parent, e);
        }
    }

    /** Where the round's CLI writes its stderr: a policy the environment applies at the launch. */
    StderrPolicy stderr() {
        return StderrPolicy.toFile(directory.resolve(STDERR_FILE));
    }

    /**
     * The last {@link #STDERR_TAIL_BYTES} bytes of the round's stderr, as agent text; empty when the
     * process wrote none or never started.
     *
     * @throws IOException if the file exists but cannot be read
     */
    UntrustedText stderrTail() throws IOException {
        Path file = directory.resolve(STDERR_FILE);
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(Math.max(0, Files.size(file) - STDERR_TAIL_BYTES));
            return UntrustedText.agent(new String(in.readNBytes(STDERR_TAIL_BYTES), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return UntrustedText.agent("");
        }
    }

    /**
     * The tail a failed round carries in its failure's detail: {@link #stderrTail}, or — when the
     * file exists but cannot be read — a factory sentence saying so, since the detail must not
     * fail the round it describes.
     *
     * @return the tail as agent text, empty when nothing was written; never null
     */
    UntrustedText tailForFailure() {
        try {
            return stderrTail();
        } catch (IOException e) {
            return UntrustedText.factory("the agent CLI's stderr in " + directory + " could not be read: " + e);
        }
    }

    /**
     * Writes the tail of a successful round's stderr as one DEBUG line, and nothing when the CLI
     * wrote none; a tail that cannot be read is a DEBUG line naming the directory. Never throws.
     */
    void logTailOnSuccess() {
        try {
            UntrustedText tail = stderrTail();
            if (!tail.isBlank()) {
                log.debug("agent CLI stderr of the round, last {} bytes at most: {}", STDERR_TAIL_BYTES, tail.forLog());
            }
        } catch (IOException e) {
            log.debug("agent CLI stderr of the round in {} could not be read", directory, e);
        }
    }

    /**
     * Removes the directory and everything in it. It cannot fail the round: a directory that
     * cannot be removed is one WARN naming it.
     */
    @Override
    public void close() {
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            log.warn(
                    OperatorEvent.ROUND_SCRATCH_REMOVAL_FAILED.head()
                            + "could not remove the round scratch directory {}; it is left on the host",
                    directory,
                    e);
        }
    }
}
