package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.logtext.OperatorEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two ways one marked line fails to become a finding, counted across a single read, and the
 * one aggregate line that reports them. Owns the drop accounting of a parse so {@link
 * GuardDenialLog} owns only the parse: the count is what tells an operator whether they are
 * looking at one odd event or a guard whose whole output the factory no longer parses. One
 * line per read, whatever the volume: the count is what tells an operator whether they are
 * looking at one odd event or a guard whose whole output the factory no longer parses. The
 * first malformed line's reason rides along so the aggregate is still diagnosable.
 */
final class GuardDenialDrops {

    private static final Logger log = LoggerFactory.getLogger(GuardDenialDrops.class);

    private int malformed;
    private int withoutHost;
    private @Nullable String firstReason;

    void malformed(String reason) {
        malformed++;
        if (firstReason == null) {
            firstReason = reason;
        }
    }

    void withoutHost() {
        withoutHost++;
    }

    void report(String key) {
        if (malformed + withoutHost == 0) {
            return;
        }
        log.warn(
                OperatorEvent.GUARD_DENIAL_EVENTS_DROPPED.head()
                        + "dropped {} unparseable guard denial event(s) for {} ({} malformed, {} without a host);"
                        + " these denials are missing from the findings. First malformed reason: {}",
                malformed + withoutHost,
                key,
                malformed,
                withoutHost,
                firstReason == null ? "none" : firstReason);
    }
}
