package com.github.oinsio.gnomish.adapter.findings;

/**
 * The publication half of the unified findings funnel (design D9): untrusted machine
 * output — check findings, judge details, gnome-authored decision questions — is published
 * to the tracker only inside a labeled fenced block, with mentions escaped, so injected
 * instructions read as data and {@code @team}-style pings never fire (FR15).
 *
 * <p>Three layers, applied in order: {@link FindingsSanitizer#strip} removes ANSI/control
 * sequences; every {@code @} gains a trailing zero-width space, breaking the mention
 * pattern while keeping the text visually intact; the block is fenced with a tilde run
 * computed to be longer than any tilde run the text itself starts a line with, so the
 * content cannot close the fence early and smuggle markdown out of it. The same rendering
 * is safe for the operator console — sanitization already neutralized terminal escapes.
 *
 * <p>Implements FR15 of add-sandbox-core.
 */
public final class TrackerFence {

    private static final String LABEL = "Untrusted machine output:";

    private static final int MIN_FENCE_LENGTH = 4;

    private TrackerFence() {}

    /**
     * Renders {@code text} as a labeled fenced block of untrusted machine output, control
     * sequences stripped and mentions escaped (FR15).
     *
     * @param text the untrusted text to publish; never null
     * @return the labeled, fenced, escaped block; never null
     */
    public static String fence(String text) {
        String inert = FindingsSanitizer.strip(text).replace("@", "@\u200B");
        String fence = "~".repeat(fenceLength(inert));
        return LABEL + "\n" + fence + "\n" + inert + "\n" + fence;
    }

    /**
     * A fence must be strictly longer than any tilde run opening a line of the content —
     * a shorter or equal run inside the block would close the fence early (FR15).
     */
    private static int fenceLength(String text) {
        int longest = 0;
        for (String line : text.split("\n", -1)) {
            int run = 0;
            while (run < line.length() && line.charAt(run) == '~') {
                run++;
            }
            longest = Math.max(longest, run);
        }
        return Math.max(MIN_FENCE_LENGTH, longest + 1);
    }
}
