package com.github.oinsio.gnomish.untrustedtext;

/**
 * The comment plane's rendering: untrusted text bound for a tracker comment is published inside a
 * labeled fenced block, with mentions and issue references broken, so injected instructions read
 * as data, {@code @team}-style pings never fire and {@code #123} never cross-links an unrelated
 * issue. The third exit from {@link CharacterTable}, beside {@link LineFlattening} (the log
 * plane, one event one line) and {@link ConsoleNotation} (the operator's terminal, hostile
 * characters shown rather than removed).
 *
 * <p>Its reader is three readers at once — a markdown renderer, a person, and the next model to
 * read the thread — which is why it needs all three layers and why, unlike the log plane, it keeps
 * the line structure: a comment is read, not grepped.
 *
 * <p>Three layers, in order: {@link TextSafety#strip} removes escape sequences and neutralized
 * characters (which also removes any zero-width space the text arrived with, so the ones below are
 * ours); every {@code @} and {@code #} gains a trailing zero-width space, breaking the mention and
 * reference patterns while keeping the text visually intact; and the block is fenced with a tilde
 * run computed to be longer than any tilde run the content itself opens a line with, so the
 * content cannot close the fence early and smuggle markdown out of it.
 *
 * <p>Moved here from {@code app.findings.TrackerFence} (design D7 of type-untrusted-text), which
 * now delegates: the carrier's comment exit and the tracker publication facade must be one
 * rendering, and this leaf is what both can reach.
 *
 * <p>Implements FR2, NFR-S2 of type-untrusted-text; originally FR15 of add-sandbox-core.
 */
final class CommentFencing {

    private static final String LABEL = "Untrusted machine output:";

    private static final int MIN_FENCE_LENGTH = 4;

    private CommentFencing() {}

    /**
     * Renders {@code text} as a labeled fenced block of untrusted machine output.
     *
     * @param text the raw untrusted text; never null
     * @return the labeled, fenced, escaped block; never null
     */
    static String render(String text) {
        String inert = inert(text);
        String fence = "~".repeat(fenceLength(inert));
        return LABEL + "\n" + fence + "\n" + inert + "\n" + fence;
    }

    /**
     * The first two layers alone — stripped, mentions and issue references broken — with no label
     * and no fence: the shape a single untrusted <em>field</em> takes inside a line the factory
     * wrote itself (design D6 of type-untrusted-text, revised 2026-09-19).
     *
     * <p>The fence exists to say "everything between these markers is machine output". A report
     * whose prose is the factory's own cannot make that statement about itself — fencing it whole
     * labels the factory's own instruction lines as untrusted output, which is the alternative D7
     * rejects. So a report bound for the tracker renders each captured field through this, and the
     * fence is kept for what it describes: a block that really is machine output end to end.
     *
     * @param text the raw untrusted text; never null
     * @return the stripped, mention-broken text, no label and no fence; never null
     */
    static String inert(String text) {
        return TextSafety.strip(text).replace("@", "@​").replace("#", "#​");
    }

    /**
     * A fence must be strictly longer than any tilde run opening a line of the content — a shorter
     * or equal run inside the block would close the fence early.
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
