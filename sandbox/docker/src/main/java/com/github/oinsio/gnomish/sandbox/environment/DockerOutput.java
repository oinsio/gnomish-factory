package com.github.oinsio.gnomish.sandbox.environment;

import java.util.Arrays;
import java.util.List;

/**
 * Parses the newline-delimited {@code --format} output of a {@code docker} list
 * command into clean lines: split on newlines, strip each, drop the blanks (the
 * trailing empty segment a final newline leaves, and any stray blank line). One
 * home for the parsing shared by the orphan sweep and the aged-environment
 * reaper, so it is unit-tested directly rather than only through their
 * daemon-gated behaviour.
 *
 * <p>Implements FR11, NFR-R2 of add-sandbox-core.
 */
final class DockerOutput {

    private DockerOutput() {}

    /**
     * The non-blank, stripped lines of {@code stdout}.
     *
     * @param stdout the raw docker list output; never null
     * @return the parsed object names/rows, in order, with blanks removed; never null
     */
    static List<String> lines(String stdout) {
        return Arrays.stream(stdout.split("\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
