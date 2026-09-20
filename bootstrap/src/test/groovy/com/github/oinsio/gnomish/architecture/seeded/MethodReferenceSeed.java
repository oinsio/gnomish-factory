package com.github.oinsio.gnomish.architecture.seeded;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.function.Function;

/**
 * A class that reaches {@link UntrustedText#raw()} and {@link UntrustedText#forParsing()} as
 * <em>method references</em> while carrying neither annotation — the same laundering
 * {@link RawReadingSeed} and {@link ForParsingSeed} perform with an ordinary call, written the one
 * way a rule phrased over calls alone cannot see (FR3, FR10, design D2, D11 of
 * type-untrusted-text).
 *
 * <p>ArchUnit models {@code UntrustedText::raw} as a {@code JavaMethodReference}, not as a
 * {@code JavaMethodCall}, so {@code callMethodWhere} never visits it; the gate asks
 * {@code accessTargetWhere} instead, which visits every access shape.
 */
public final class MethodReferenceSeed {

    private MethodReferenceSeed() {}

    /**
     * Launders a whole list of carriers through a method reference.
     *
     * @param texts the carriers to read; never null
     * @return their raw texts, unneutralized
     */
    public static List<String> launder(List<UntrustedText> texts) {
        return texts.stream().map(UntrustedText::raw).toList();
    }

    /**
     * Reaches the captured bytes through a method reference without declaring itself a parser.
     *
     * @return the function that hands the captured bytes over
     */
    public static Function<UntrustedText, String> undeclared() {
        return UntrustedText::forParsing;
    }
}
