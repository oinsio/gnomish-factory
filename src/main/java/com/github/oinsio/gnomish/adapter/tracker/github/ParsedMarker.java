package com.github.oinsio.gnomish.adapter.tracker.github;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The result of parsing a GitHub structural comment body back into its
 * coordination fields (design D9, FR7 of add-tracker-port): the marker
 * {@code kind}, the {@code instance} that posted it, the {@code at}
 * timestamp, the marker format {@code version}, the {@code humanText}
 * remainder — the plain-text line(s) that followed the hidden HTML comment
 * in the original comment body, verbatim — and the optional {@code reason}
 * field a {@code report}-kind park marker carries (task 4.10 judgment call:
 * design D9's marker-kind vocabulary has no dedicated {@code park} kind, so a
 * park is a {@code report}-kind marker whose wire-level {@code reason} field
 * this type exposes; every other kind leaves it {@code null}).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR7 of add-tracker-port.
 *
 * @param kind the recognized marker kind
 * @param instance the identifier of the instance that posted the marker; never blank
 * @param at when the marker was created; never null
 * @param version the structural-JSON format version the marker was rendered with
 * @param humanText the human-readable text following the structural comment line
 * @param reason the wire value of the park reason for a {@code report}-kind park
 *     marker, or {@code null} for every other marker
 */
public record ParsedMarker(
        GithubMarkerKind kind,
        String instance,
        Instant at,
        int version,
        String humanText,
        @Nullable String reason) {}
