package com.github.oinsio.gnomish.gittransfer;

import java.util.Objects;

/**
 * The one refspec a transfer names — a {@code source:destination} pair, or a bare object name —
 * as a value the owner places after the options terminator. Two shapes are refused where the value
 * is made rather than where git would misread them: an empty refspec, which names nothing, and one
 * whose first character is {@code -}, which {@code git fetch} — parsing options after the remote
 * name too — would honour as an option (FR3's "options terminator" scenario has the terminator;
 * this is the second lock on the same door).
 *
 * <p>Nothing else is checked here: the ref-name grammar belongs to the resolution policy that
 * produces the names, and git's own {@code check-ref-format} refuses the rest.
 *
 * <p>Implements FR1, FR3 of own-git-transfer-argv.
 *
 * @param value the refspec text, verbatim
 */
public record Refspec(String value) {

    public Refspec {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a refspec must not be blank");
        }
        if (value.startsWith("-")) {
            throw new IllegalArgumentException("a refspec must not start with '-': " + value);
        }
    }
}
