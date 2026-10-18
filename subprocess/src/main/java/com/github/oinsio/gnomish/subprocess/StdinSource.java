package com.github.oinsio.gnomish.subprocess;

/**
 * What a child reads on its standard input (design D20 of fix-terminal-receipt-convergence): nothing
 * at all, or bytes complete before the launch. {@link ProcessLaunch#start(ProcessBuilder,
 * StdinSource)} applies it — {@link #NONE} as {@code /dev/null}, {@link #of(byte[])} as a file the
 * owner writes, hands the child and deletes — so no launch leaves a pipe on the child's stdin for the
 * factory to write into and for a descendant to inherit. Streaming input has no form here (NG16).
 *
 * <p>Implements FR23 of fix-terminal-receipt-convergence.
 */
public sealed interface StdinSource {

    /** No input: the child reads end-of-file at once. */
    StdinSource NONE = new Nothing();

    /**
     * The whole of the child's input.
     *
     * @param content the bytes the child reads from its standard input, copied here, so a later
     *     change by the caller does not reach the child
     * @return the source the launch owner writes into the child's input file
     */
    static StdinSource of(byte[] content) {
        return new Content(content.clone());
    }

    /** The empty source behind {@link #NONE}. */
    final class Nothing implements StdinSource {

        private Nothing() {}
    }

    /** Bytes for the child, complete before the launch. */
    final class Content implements StdinSource {

        private final byte[] content;

        private Content(byte[] content) {
            this.content = content;
        }

        /** The bytes themselves, for the launch owner's file; never handed out of the module. */
        byte[] bytes() {
            return content;
        }
    }
}
