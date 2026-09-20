package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.untrustedtext.TextSafety;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * Which human plane a {@link StatusReport} render is bound for, and therefore which exit its
 * untrusted fields take (design D6 of type-untrusted-text, revised 2026-09-19). The same report is
 * printed to the operator's terminal and published to the tracker, and those two readers need
 * different neutralizations — so the plane is chosen by the caller that knows where the text is
 * going, once, instead of being re-applied to the finished block.
 *
 * <p>The failure this exists for: with a console-only render, the finish path compensated by
 * minting the whole assembled report as a carrier and fencing it at {@code tracker.finish}. That
 * rendered every field twice and put the factory's own report lines inside an "untrusted machine
 * output" label — the alternative design D7 rejects for the park report, arriving through the
 * other door.
 *
 * <p>Implements FR2, D6, D7 of type-untrusted-text.
 */
public enum ReportPlane {

    /**
     * The operator's terminal: hostile characters shown rather than removed, line structure and
     * length kept, because an operator being attacked must see the attempt.
     */
    CONSOLE {
        @Override
        public String render(UntrustedText text) {
            return text.forConsole();
        }

        @Override
        public String block(UntrustedText text) {
            return text.forConsole();
        }

        @Override
        String line(String text) {
            return LogText.forLog(text);
        }
    },

    /**
     * A tracker comment: stripped, mentions and issue references broken, no fence — the report's
     * prose is the factory's own, so only its quoted fields are machine output and only they are
     * neutralized. The writer publishes what this produces verbatim.
     */
    COMMENT {
        @Override
        public String render(UntrustedText text) {
            return text.forCommentInline();
        }

        @Override
        public String block(UntrustedText text) {
            return text.forComment();
        }

        @Override
        String line(String text) {
            // Two rules, not two renderings of one: the findings funnel keeps a hostile locator
            // from forging report rows (one finding, one capped line), and the comment plane keeps
            // it from pinging a team. The funnel's own output is what the mention break is applied
            // to, exactly as a console line would be printed.
            return TextSafety.forCommentInline(LogText.forLog(text));
        }
    };

    /**
     * Renders one untrusted field quoted inside a line the factory wrote itself.
     *
     * @param text the carrier to render; never null
     * @return the plane's rendering; never null
     */
    public abstract String render(UntrustedText text);

    /**
     * Renders one untrusted capture that is machine output end to end — a heading of the factory's
     * own followed by nothing but the capture. On the comment plane that is the labeled fenced
     * shape, the one statement a fence can honestly make (design D6, revised 2026-09-19); on the
     * console plane it is the same rendering {@link #render} gives, because the label and the fence
     * are markdown devices a terminal only reads as noise.
     *
     * @param text the carrier to render; never null
     * @return the plane's rendering of a whole-capture block; never null
     */
    public abstract String block(UntrustedText text);

    /**
     * Renders one line of untrusted text that is not yet carried — a finding's message or locator,
     * which passes the findings funnel on every plane.
     *
     * @param text the raw line; never null
     * @return the plane's rendering, one line; never null
     */
    abstract String line(String text);
}
