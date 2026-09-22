package com.github.oinsio.gnomish.untrustedtext;

import java.util.Objects;

/**
 * Text that entered the factory from outside its trust boundary, carried with the family it came
 * from (FR1, FR2, FR3, design D1 of type-untrusted-text). Subprocess output, in-container command
 * output, agent output, tracker strings, the target repository's manifest and task-branch
 * documents are all attacker-influenced; this is the one type they travel in, from the moment they
 * are read to the moment a sink renders them.
 *
 * <p>Three properties make the type do work a {@code String} cannot. <b>The trust status is in
 * the type</b>: a signature taking {@code UntrustedText} cannot be handed factory-authored text by
 * accident, and a signature still taking {@code String} after this change is a place the compiler
 * says untrusted text does not reach. <b>Exits, not access</b>: {@link #forLog()},
 * {@link #forConsole()}, {@link #forComment()} and its inline shape {@link #forCommentInline()} are
 * the ways out, one per plane, each exactly
 * the {@link TextSafety} primitive for the same raw text ({@code UntrustedTextExitIdentitySpec});
 * {@link #raw()} exists for the writers carrying bytes to a machine medium and is confined to
 * {@link UntrustedExit} classes by the architecture gate. <b>Concatenation is safe</b>:
 * {@link #toString()} is {@link #forLog()} byte for byte, so the classic laundering move — dropping
 * the value into a factory-authored message — yields neutralized text rather than a hole.
 *
 * <p>That last property is why this is a final class and not a record: a record's canonical
 * {@code toString()} prints the raw text, the exact defect this change closes. Rendering rather
 * than throwing there is deliberate too (design D1) — a {@code catch} block building a message
 * from a carrier would otherwise crash the crash handler, and SLF4J's {@code {}} formatting calls
 * {@code toString()} on the appender thread, where a throw is swallowed into a Logback status
 * message, i.e. a lost record. The gate still asks for an explicit exit at every sink, so intent
 * stays visible at the call site.
 *
 * <p>Implements FR1, FR2, FR3 of type-untrusted-text.
 */
@UntrustedExit
public final class UntrustedText {

    /**
     * The smallest bound {@link #cappedTo(int)} accepts. Below it the head share is shorter than
     * the truncation's own line-snap window, so the snap could reach past the start of the text —
     * and a head-and-tail cap with an omission marker says nothing useful at that size anyway.
     */
    public static final int MIN_CAP_CHARS = 1_024;

    /**
     * The smallest bound {@link #excerpt(int)} accepts: twice the room its truncation marker
     * reserves inside the bound, so an excerpt that was cut still says more than its own marker
     * does. Below it the output bound could not be honoured at all — the marker alone would not
     * fit — and no call site wants an excerpt that small (the shortest in the factory is a
     * decision-file preview at 500).
     */
    public static final int MIN_EXCERPT_CAP_CHARS = 2 * TextSafety.TRUNCATION_MARKER_RESERVE;

    private final String raw;

    private final Provenance provenance;

    private UntrustedText(String raw, Provenance provenance) {
        this.raw = Objects.requireNonNull(raw, "raw text must not be null");
        this.provenance = provenance;
    }

    /**
     * Mints text captured from a process the factory launched on the host — git, docker, an agent
     * CLI, a verify command; at the first factory reader, after credential scrubbing (design D3).
     *
     * @param text the raw captured text; never null
     * @return the carrier; never null
     */
    public static UntrustedText subprocess(String text) {
        return new UntrustedText(text, Provenance.SUBPROCESS);
    }

    /**
     * Mints output of a command run inside a sandbox box, as it crosses back over the box boundary.
     *
     * @param text the raw captured text; never null
     * @return the carrier; never null
     */
    public static UntrustedText container(String text) {
        return new UntrustedText(text, Provenance.CONTAINER);
    }

    /**
     * Mints text a coding agent or a model produced: stream events, decision files, verdict details.
     *
     * @param text the raw agent text; never null
     * @return the carrier; never null
     */
    public static UntrustedText agent(String text) {
        return new UntrustedText(text, Provenance.AGENT);
    }

    /**
     * Mints text the task tracker holds — titles, bodies, comments, claim markers. A fixture
     * tracker mints here too: a fixture is a tracker.
     *
     * @param text the raw tracker text; never null
     * @return the carrier; never null
     */
    public static UntrustedText tracker(String text) {
        return new UntrustedText(text, Provenance.TRACKER);
    }

    /**
     * Mints text read from the target repository's {@code .gnomish/} manifest, including the
     * messages a loader derives from it.
     *
     * @param text the raw manifest-derived text; never null
     * @return the carrier; never null
     */
    public static UntrustedText manifest(String text) {
        return new UntrustedText(text, Provenance.MANIFEST);
    }

    /**
     * Mints text read back from a task-branch document another instance wrote. The finer
     * provenance of whatever that instance captured is deliberately not reconstructed: the branch
     * is a medium other instances write, so the branch is the honest answer.
     *
     * @param text the raw document text; never null
     * @return the carrier; never null
     */
    public static UntrustedText branchDocument(String text) {
        return new UntrustedText(text, Provenance.BRANCH_DOCUMENT);
    }

    /**
     * Mints an argument the operator handed the process on its command line, for the one shape that
     * reaches a published report rather than a syntax gate alone: a value a grammar refused, quoted
     * back to name what has to be fixed. See {@link Provenance#OPERATOR} for why operator text —
     * inside the trust boundary everywhere else — needs an exit here.
     *
     * @param text the argument as it was typed; never null
     * @return the carrier; never null
     */
    public static UntrustedText operator(String text) {
        return new UntrustedText(text, Provenance.OPERATOR);
    }

    /**
     * Mints a sentence the factory composed itself, for a field whose type is this carrier because
     * the same field holds captured text on another path — a refusal, a disposition, an
     * explanation of what failed. Nothing minted here crossed the trust boundary; see
     * {@link Provenance#FACTORY} for why such text is a carrier at all.
     *
     * <p>Quoting captured text in the sentence is allowed and stays factory prose, provided the
     * quote left its own carrier through an exit ({@link #forLog()}, {@link #forConsole()},
     * {@link #forComment()}, {@link #forCommentInline()}, or the {@link #toString()} that is the
     * log exit) before being
     * concatenated in. What must never be minted here is text the factory only passed along:
     * that keeps the family it was captured in.
     *
     * @param text the factory-composed sentence; never null
     * @return the carrier; never null
     */
    public static UntrustedText factory(String text) {
        return new UntrustedText(text, Provenance.FACTORY);
    }

    /**
     * The capture family this text came from — evidence for reports, never a policy input: all
     * families are equally untrusted and render identically.
     *
     * @return the provenance; never null
     */
    public Provenance provenance() {
        return provenance;
    }

    /**
     * The text exactly as it arrived. Reserved for {@link UntrustedExit} classes — the exits below
     * and the writers carrying bytes to a machine medium — and enforced there by the architecture
     * gate. Every other reader wants an exit.
     *
     * @return the raw untrusted text; never null
     */
    public String raw() {
        return raw;
    }

    /**
     * The parsing exit: the text exactly as it arrived, for turning into a value that is no longer
     * untrusted text. Reserved for {@link UntrustedParser} classes and enforced there by the
     * architecture gate — a parse needs the bytes uncapped and unflattened, which is the one thing
     * an exit cannot give it, so machine-readable capture leaves through its own way out rather
     * than widening the exit allowlist (design D11).
     *
     * @return the raw untrusted text; never null
     */
    public String forParsing() {
        return raw;
    }

    /**
     * Whether the captured text is blank. A question about the text, not a way out of the carrier:
     * a boolean carries no text, so every caller may ask (design D11).
     *
     * @return true if the text is empty or whitespace only
     */
    public boolean isBlank() {
        return raw.isBlank();
    }

    /**
     * Whether the captured text contains a marker the caller knows — a subprocess's {@code already
     * exists}, a daemon's refusal. A question, like {@link #isBlank()}: open to every caller.
     *
     * @param marker the factory-authored substring to look for; never null
     * @return true if the text contains it
     */
    public boolean contains(String marker) {
        return raw.contains(Objects.requireNonNull(marker, "marker must not be null"));
    }

    /**
     * How long the captured text is, in characters. A question, like {@link #isBlank()}.
     *
     * @return the length of the raw text
     */
    public int length() {
        return raw.length();
    }

    /**
     * The log exit: one inert line, bounded at {@link TextSafety#DEFAULT_CAP_CHARS} characters of
     * the tail, where the error is.
     *
     * @return the log-safe rendering; never null, never containing a line break
     */
    public String forLog() {
        return TextSafety.forLog(raw, TextSafety.DEFAULT_CAP_CHARS);
    }

    /**
     * The log exit under a caller's own bound, for a site whose useful excerpt is shorter (a
     * decision-file preview) or longer (a captured build log) than the default. Unlike
     * {@link #forLog()}, the bound is on what <b>leaves</b>: the result is never longer than
     * {@code cap} characters, whatever the captured text renders into (FR10, design D9 of
     * fix-envelope-medium).
     *
     * <p>That is why the caller's bound and the default one differ. {@link TextSafety#forLog}
     * caps the <em>input</em> to the flattening, so a text of nothing but {@code U+2028} leaves
     * as six characters per one — bounded, but six times the number asked for. At
     * {@link TextSafety#DEFAULT_CAP_CHARS} that is the sink's problem and the sink's guarantee
     * (it caps the rendered record, keeping the head). Here it is the caller's: every caller of
     * this method quotes the result inside prose of its own and sized its bound so that prose
     * fits under the record cap, and an excerpt six times its bound eats exactly that headroom.
     * So a second tail cap runs after the flattening, under a bound reduced by
     * {@link TextSafety#TRUNCATION_MARKER_RESERVE} so its own marker fits inside {@code cap},
     * and the flattening runs once more — on already-flattened text it is the identity, and it
     * neutralizes the newline that marker is written with, keeping one event on one line. A cut
     * that lands inside a rendered escape leaves inert ASCII the marker has already named.
     *
     * @param cap the maximum characters of the rendered result; at least
     *     {@link #MIN_EXCERPT_CAP_CHARS}
     * @return the log-safe rendering, of at most {@code cap} characters; never null, never
     *     containing a line break
     * @throws IllegalArgumentException if {@code cap} is below {@link #MIN_EXCERPT_CAP_CHARS}
     */
    public String excerpt(int cap) {
        if (cap < MIN_EXCERPT_CAP_CHARS) {
            throw new IllegalArgumentException("cap must be at least " + MIN_EXCERPT_CAP_CHARS + ", was " + cap);
        }
        return TextSafety.flatten(
                TextSafety.capTail(TextSafety.forLog(raw, cap), cap - TextSafety.TRUNCATION_MARKER_RESERVE));
    }

    /**
     * The same text bounded to {@code cap} characters, keeping the head and the tail and marking
     * how many characters were dropped between them — the shape a reader of a long capture needs,
     * where the log exit's tail-only cap would drop the top-level message.
     *
     * <p>Not a way out and therefore not annotated (design D13): what it returns is a carrier of
     * the same provenance, so no text leaves and the exit allowlist is unchanged. That is the
     * invariant that keeps double-rendering unrepresentable — a transformation of carried text
     * returns the carrier.
     *
     * @param cap the maximum characters of the result; at least {@link #MIN_CAP_CHARS}
     * @return this carrier when the text is already within the bound, else a carrier of the same
     *     provenance holding the head, the omission marker and the tail
     * @throws IllegalArgumentException if {@code cap} is below {@link #MIN_CAP_CHARS}
     */
    public UntrustedText cappedTo(int cap) {
        if (cap < MIN_CAP_CHARS) {
            throw new IllegalArgumentException("cap must be at least " + MIN_CAP_CHARS + ", was " + cap);
        }
        String capped = HeadTailCap.cap(raw, cap);
        return capped.equals(raw) ? this : new UntrustedText(capped, provenance);
    }

    /**
     * The console exit: what the log exit removes is shown instead, in caret and backslash-u
     * notation, with the line structure and the length kept — an operator being attacked must see
     * the attempt, and their report is long by design.
     *
     * @return the console-safe rendering; never null
     */
    public String forConsole() {
        return TextSafety.forConsole(raw);
    }

    /**
     * The comment exit: a labeled fenced block with mentions and issue references broken, for text
     * published to the tracker, where the readers are a markdown renderer, a person, and the next
     * model to read the thread.
     *
     * @return the fenced, labeled rendering; never null
     */
    public String forComment() {
        return TextSafety.forComment(raw);
    }

    /**
     * The comment exit's inline shape: the same neutralization — stripped, mentions and issue
     * references broken — without the label and the fence, for a field the factory quotes inside a
     * line it wrote itself. A whole report assembled by the factory takes this exit field by field;
     * {@link #forComment()} stays for what it describes, a block that is machine output end to end
     * (design D6, D7 of type-untrusted-text, revised 2026-09-19).
     *
     * @return the inert field rendering, no label and no fence; never null
     */
    public String forCommentInline() {
        return TextSafety.forCommentInline(raw);
    }

    /**
     * Identity is the text alone — provenance is deliberately outside it (design D1, revised
     * 2026-09-17). Provenance is evidence a report may name, never a policy input, and a value that
     * decides nothing has no business deciding identity: a cause minted at a subprocess, written to
     * a task-branch document and read back as a branch-document carrier is the same value, so a
     * round trip over a durable medium preserves it (NFR-R2). Were provenance inside, that identity
     * would be false by construction, a carrier in a set or a map key would stop deduplicating
     * across media without failing anything, and moving a mint — a refactor this type invites
     * family by family — would change comparison results, which a value type must never do.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof UntrustedText that && raw.equals(that.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /**
     * The log exit, byte for byte — see the class javadoc: this is the property that makes
     * concatenating a carrier into a factory-authored string safe instead of a hole.
     */
    @Override
    public String toString() {
        return forLog();
    }
}
