package com.github.oinsio.gnomish.app.daemon;

import com.github.oinsio.gnomish.status.DaemonComponent;

/**
 * Everything that distinguishes one supervised daemon loop from another except its tick (design
 * D1 of supervise-daemon-loops-and-embed-dashboard): the component its lines are framed with and
 * keyed by, the order of tick and wait, the wait, and the restart policy. A loop class builds one
 * of these in its constructor and hands it, with its own tick, to a {@link SupervisedLoop}.
 *
 * <p>Implements FR1, FR3 of supervise-daemon-loops-and-embed-dashboard.
 *
 * @param component the daemon this loop is; frames every line it emits with the {@code component}
 *     MDC key and keys its failure streak; never null
 * @param order whether a cycle ticks or waits first; never null
 * @param loopWait how the loop waits (named so because a record component cannot be called {@code wait}) between ticks; never null, owned by this one loop
 * @param policy what happens when the loop's thread dies anyway; never null, owned by this one loop
 */
public record LoopShape(DaemonComponent component, LoopOrder order, LoopWait loopWait, RestartPolicy policy) {}
