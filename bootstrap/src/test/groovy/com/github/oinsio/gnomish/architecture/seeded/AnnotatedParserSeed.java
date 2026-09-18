package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Optional;

/**
 * The other half of rule (a2)'s seeded pair: the same read of {@link UntrustedText#forParsing()},
 * in a class that declares itself a parser and converts what it reads. Without it a green rule
 * could mean "the annotation is ignored" as easily as "the exemption works" (FR10, design D11 of
 * type-untrusted-text).
 */
@UntrustedParser
public final class AnnotatedParserSeed {

    private AnnotatedParserSeed() {}

    /**
     * Parses a commit id out of captured output the way a parser may. The returned string is inert
     * by its fixed shape, checked here: forty lowercase hexadecimal characters and nothing else.
     *
     * @param captured the carrier to parse; never null
     * @return the commit id, or empty when the output is not one
     */
    public static Optional<String> commitId(UntrustedText captured) {
        String candidate = captured.forParsing().strip();
        return candidate.matches("[0-9a-f]{40}") ? Optional.of(candidate) : Optional.empty();
    }
}
