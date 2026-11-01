package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.UsageFold;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;

/**
 * The cells of {@link UsageMarkdownRenderer}'s tables: how one value is spelled in a Markdown
 * report bound for a pull request comment. Formatting only — every number it is handed is already
 * a sum of the one {@link UsageFold}, and the token counts are counted together by the fold too
 * (design D16 of define-executor-contract).
 *
 * <p>A cost cell is not spelled here: {@code status.UsageCostWords} owns the words of a cost —
 * a lower bound included — for every plane that shows one (FR20, UX4, design D15). A name the task
 * branch's own records carry — a stage, a model id — crossed
 * the trust boundary, so it leaves through the comment plane's inline exit and has its table
 * separator escaped, keeping a hostile name inside its own cell.
 *
 * <p>Implements FR20 of define-executor-contract.
 */
final class UsageMarkdownCells {

    private UsageMarkdownCells() {}

    /**
     * A name read from the task branch as one table cell.
     *
     * @param name the raw name; never null
     * @return the neutralized cell text, its pipes escaped and its line breaks folded to spaces
     */
    static String name(String name) {
        return UntrustedText.branchDocument(name)
                .forCommentInline()
                .replace("|", "\\|")
                .replace('\n', ' ');
    }

    /** One figure's four kinds counted together, as one cell. */
    static String count(TokenUsage tokens) {
        return Long.toString(UsageFold.count(tokens));
    }

    /** The four kinds of one figure and their count, as five cells of a row. */
    static String kinds(TokenUsage tokens) {
        return tokens.input() + " | " + tokens.output() + " | " + tokens.cacheCreation() + " | " + tokens.cacheRead()
                + " | " + count(tokens);
    }

    /**
     * A wall time in the largest two units that matter: hours and minutes, minutes and seconds, or
     * seconds.
     *
     * @param duration the time; never null, never negative
     * @return the rendered time
     */
    static String duration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds >= 3600) {
            return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
        }
        if (seconds >= 60) {
            return seconds / 60 + " min " + seconds % 60 + " s";
        }
        return seconds + " s";
    }
}
