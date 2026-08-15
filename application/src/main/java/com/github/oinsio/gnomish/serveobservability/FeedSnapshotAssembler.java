package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.FeedView;

/**
 * Builds the snapshot's {@code feed} section (FR5) from {@link FeedAutomaton#view()}: maps
 * {@code app.serve}'s {@link com.github.oinsio.gnomish.app.serve.FeedState} onto this package's
 * decoupled {@link FeedPhase} by enum name — the two enums are kept distinct so the document
 * model has no compile-time dependency on the serve-wiring package ({@link FeedPhase}'s own
 * Javadoc) — and carries {@code since}, {@code lastPollAt}, {@code openFronts}, and {@code
 * wipLimit} across verbatim.
 *
 * <p>Stateless: holds no fields, only assembles a fresh {@link FeedSnapshot} from the view handed
 * to it on each call.
 *
 * <p>Implements FR5 of add-serve-observability.
 */
public final class FeedSnapshotAssembler {

    private FeedSnapshotAssembler() {}

    /**
     * Assembles the {@code feed} section from {@code automaton}'s current view.
     *
     * @param automaton the feed automaton whose current {@link FeedAutomaton#view()} becomes the
     *     snapshot's {@code feed} section; never null
     * @return the assembled {@link FeedSnapshot}; never null
     */
    public static FeedSnapshot assemble(FeedAutomaton automaton) {
        return assemble(automaton.view());
    }

    /**
     * Assembles the {@code feed} section from a raw {@link FeedView}.
     *
     * @param view the automaton's observability view to translate; never null
     * @return the assembled {@link FeedSnapshot}; never null
     */
    public static FeedSnapshot assemble(FeedView view) {
        return new FeedSnapshot(
                FeedPhase.valueOf(view.state().name()),
                view.since(),
                view.lastPollAt(),
                view.openFronts(),
                view.wipLimit());
    }
}
