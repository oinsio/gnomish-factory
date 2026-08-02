package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.serve.FeedAutomaton;

/**
 * The seam between {@link ServeCommand} and the feed loop's blocking {@link FeedAutomaton#run()}
 * (task 5.1 of add-factory-serve). Production always uses the default {@link FeedAutomaton#run}
 * method reference, which never returns except via {@link InterruptedException} (see {@link
 * FeedAutomaton#run}'s own Javadoc). A spec substitutes a fake that returns immediately instead of
 * blocking forever, so a startup-only assertion ("the scheduler was built and started") can run to
 * completion without ever driving a real feed cycle — the automaton itself is still fully built by
 * {@link ServeCommand#run}, only the call that would block is swapped out.
 *
 * <p>Implements D3, D7 of add-factory-serve.
 */
@FunctionalInterface
interface FeedAutomatonStarter {

    /**
     * Starts {@code automaton} running (production: {@link FeedAutomaton#run()}).
     *
     * @param automaton the fully-wired feed automaton; never null
     * @throws InterruptedException once the calling thread is interrupted (production only)
     */
    void start(FeedAutomaton automaton) throws InterruptedException;
}
