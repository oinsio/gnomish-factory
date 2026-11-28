package com.github.oinsio.gnomish.e2e.paidsmoke

import com.github.oinsio.gnomish.sandbox.ExecHandle
import groovy.transform.CompileStatic
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.InstantSource

/**
 * A child's stdout read line by line, each line stamped with the instant it arrived — the
 * measurement {@link PaidSmokeLivenessSpec} judges a dialect's liveness cadence by (FR17 of
 * extract-agent-round-runner, design D6 "A declared liveness is tested, per dialect"). The output
 * is read through the handle's non-parking stream view, never the bare pipe.
 *
 * <p>Statically compiled for the reason {@link PaidSmokeAgentLauncher} states.
 *
 * <p>Implements FR17 of extract-agent-round-runner.
 */
@CompileStatic
final class StampedStdout {

    /** One stdout line and the instant it arrived. */
    static final class Line {
        final Instant at
        final String text

        Line(Instant at, String text) {
            this.at = at
            this.text = text
        }
    }

    final Instant startedAt
    final List<Line> lines

    private StampedStdout(Instant startedAt, List<Line> lines) {
        this.startedAt = startedAt
        this.lines = lines.asImmutable()
    }

    /**
     * Reads {@code handle}'s stdout to its end, stamping every line on arrival through {@code clock}.
     *
     * @return the stamped lines, in arrival order
     */
    static StampedStdout read(ExecHandle handle, InstantSource clock) {
        List<Line> lines = []
        new BufferedReader(new InputStreamReader(handle.output().stream(), StandardCharsets.UTF_8)).withCloseable { reader ->
            String text
            while ((text = reader.readLine()) != null) {
                lines << new Line(clock.instant(), text)
            }
        }
        new StampedStdout(handle.startedAt(), lines)
    }

    /** The largest gap between two consecutive lines; zero with fewer than two lines. */
    Duration largestGap() {
        Duration largest = Duration.ZERO
        for (int i = 1; i <lines.size(); i++) {
            Duration gap = Duration.between(lines[i - 1].at, lines[i].at)
            if (gap> largest) {
                largest = gap
            }
        }
        largest
    }

    /** The last line containing {@code fragment}, or null when none does. */
    Line last(String fragment) {
        lines.reverse().find { Line line -> line.text.contains(fragment) }
    }

    /** How long after the start {@code line} arrived. */
    Duration sinceStart(Line line) {
        Duration.between(startedAt, line.at)
    }
}
