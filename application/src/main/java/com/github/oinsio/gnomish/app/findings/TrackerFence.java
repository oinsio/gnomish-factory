package com.github.oinsio.gnomish.app.findings;

import com.github.oinsio.gnomish.untrustedtext.TextSafety;

/**
 * The publication half of the unified findings funnel (design D9 of add-sandbox-core): untrusted
 * machine output — check findings, judge details, gnome-authored decision questions — is published
 * to the tracker only inside a labeled fenced block, with mentions and issue references escaped,
 * so injected instructions read as data and {@code @team}-style pings never fire (FR15).
 *
 * <p>A <b>facade</b>, like {@link FindingsSanitizer} beside it: the rendering itself is
 * {@link TextSafety#forComment} in the JDK-only {@code :untrustedtext} leaf, which is also the
 * comment exit of {@code UntrustedText} (design D7 of type-untrusted-text). It moved there so the
 * carrier and this entry point cannot be two renderings of one rule — a caller holding a carrier
 * calls its exit, a caller still holding a {@code String} calls here, and both get the same block.
 *
 * <p>Implements FR15 of add-sandbox-core; FR2, NFR-S2 of type-untrusted-text.
 */
public final class TrackerFence {

    private TrackerFence() {}

    /**
     * Renders {@code text} as a labeled fenced block of untrusted machine output, control
     * sequences stripped and mentions escaped (FR15).
     *
     * @param text the untrusted text to publish; never null
     * @return the labeled, fenced, escaped block; never null
     */
    public static String fence(String text) {
        return TextSafety.forComment(text);
    }
}
