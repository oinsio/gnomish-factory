package com.github.oinsio.gnomish.untrustedtext;

/**
 * Where a piece of untrusted text entered the factory — the capture family that minted it
 * (design D3 of type-untrusted-text). One constant per family, and the set is closed: a text
 * with no family here has no mint, which is the point.
 *
 * <p>Provenance is <b>evidence, not policy</b>. It changes no rendering — every exit computes the
 * same answer for the same raw text whatever the family — and no component branches on it to
 * decide how much to trust the text. What it buys is that a report or an escalation three calls
 * away can name where its text came from without carrying a second field alongside it, and that a
 * reader of a log line knows whether they are looking at a subprocess's complaint or at the target
 * repository's own manifest.
 *
 * <p>Every family but {@link #FACTORY} names a source outside the trust boundary, and those are
 * equally untrusted: none is safer than another. {@code FACTORY} is the one family for text the
 * factory wrote itself, and it exists so that a sentence the factory composed is never filed under
 * a source that did not write it (design D3).
 *
 * <p>Implements FR1, FR4 of type-untrusted-text.
 */
public enum Provenance {

    /** A process the factory launched on the host: git, docker, an agent CLI, a verify command. */
    SUBPROCESS("subprocess output"),

    /** A command run inside a sandbox box, whose output crosses back over the box boundary. */
    CONTAINER("in-container command output"),

    /** A coding agent or a model: its stream events, its decision file, its verdicts. */
    AGENT("agent output"),

    /** The task tracker: issue titles and bodies, comments, labels, claim markers. */
    TRACKER("tracker text"),

    /** The target repository's {@code .gnomish/} manifest and everything derived from it. */
    MANIFEST("target-repository manifest"),

    /** A task-branch document another factory instance wrote: {@code task.json}, {@code state.json}. */
    BRANCH_DOCUMENT("task-branch document"),

    /**
     * An argument the operator handed the process on its command line — {@code --base} is the
     * first, and so far the only, one that reaches a report rather than a syntax gate alone.
     *
     * <p>The operator is inside the trust boundary, so this family is not here because the text is
     * attacker-influenced. It is here because the one value that reaches this constant is a value a
     * grammar has just <em>refused</em> ({@code RefNameSyntax}), and the refusal is published to
     * the tracker, where the readers are a markdown renderer, a person and the next model to read
     * the thread. A refused ref name is refused precisely for holding whitespace or a control
     * character, so quoting it back raw is the one place operator text needs an exit like any
     * other (design D1, extended at task 6.1 of type-untrusted-text).
     */
    OPERATOR("operator-supplied argument"),

    /**
     * The factory's own prose: a sentence it composed to explain a refusal, a failure or a
     * disposition. Nothing here came from outside the trust boundary.
     *
     * <p>It is a family of this enum because the field it lands in is a carrier — the same {@code
     * reason}, {@code cause} or {@code summary} that holds captured text on the next code path —
     * and a carrier is what keeps every sink honest about it. The alternative, minting the
     * sentence in the family of the capture it happens to sit beside, files factory prose under a
     * source that did not write it; the alternative of typing the field {@code String} splits one
     * field into two shapes a reader has to tell apart. This family is how a composed sentence
     * stays a carrier without claiming a provenance it does not have.
     *
     * <p>A sentence that <em>quotes</em> captured text is factory prose too, as long as the quote
     * left its own carrier through an exit first: what the field then holds is the factory's
     * sentence, with a neutralized excerpt inside it, and the excerpt's family belongs to the
     * carrier it was rendered from rather than to the sentence built around it.
     */
    FACTORY("factory-composed text");

    private final String description;

    Provenance(String description) {
        this.description = description;
    }

    /**
     * Names this family in the words a report prints, so a renderer needs no table of its own.
     *
     * @return the human-readable family name; never null, never blank
     */
    public String description() {
        return description;
    }
}
