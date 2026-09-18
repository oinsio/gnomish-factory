package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
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
 *
 * <p>That reason is Jackson's echo of the offending bytes, so it is held as {@link UntrustedText}
 * from the catch that produced it until the aggregate line renders it through the log exit — the
 * text is stored here and logged later, which is exactly the shape an accessor-name gate at the
 * log call could never see (design D5 of type-untrusted-text).
 */
final class GuardDenialDrops {

    private static final Logger log = LoggerFactory.getLogger(GuardDenialDrops.class);

    private int malformed;
    private int withoutHost;
    private @Nullable UntrustedText firstReason;

    /**
     * Counts one line the parser refused, keeping the first reason as the carrier it arrived in.
     *
     * @param reason why the line did not parse, as the malformed bytes' own carrier
     */
    void malformed(UntrustedText reason) {
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
                firstReason == null ? "none" : firstReason.forLog());
    }
}
