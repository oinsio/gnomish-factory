package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One recorded egress denial: the {@link Finding} a reviewer reads, paired with
 * the {@link DenialIdentity} its source assigned it (design D5 of
 * fix-denial-attribution-durability).
 *
 * <p>A wrapper rather than two more components on {@code Finding}: a finding is
 * the shared unit of feedback every check produces, and identity is denial-source
 * bookkeeping that no check has and no report surface shows. Keeping it outside
 * {@code Finding} is what lets {@code status.json} and the text render carry the
 * finding alone while {@code state.json} and {@code task.json} carry the identity
 * beside it.
 *
 * <p>{@code identity} is {@code null} for a denial whose source could not stamp it
 * — a synthetic loss marker (design D6), or a denial read from a source whose
 * identity the factory could not resolve. Such a denial reads as "unknown, keep":
 * it never matches a recorded denial, so it is attached rather than merged away,
 * which is the duplicate-over-silence stance of design D3.
 *
 * <p>Implements FR7 of fix-denial-attribution-durability.
 *
 * @param finding what was denied; never null
 * @param identity the source-assigned identity, or {@code null} when the source assigned none
 */
public record Denial(Finding finding, @Nullable DenialIdentity identity) {

    /**
     * A denial no source could stamp — a loss marker, or a read with no resolvable source.
     *
     * <p>PIT documented exception (`.claude/rules/testing.md`, JVMTI redefinition limit):
     * {@code @DoNotMutate} because PIT's Gregor engine crashes its own minion JVM (RUN_ERROR,
     * not a real test gap) mutating this record's static factory on JDK 17+
     * (hcoles/pitest#1285, a JVMTI RedefineClasses restriction on NestHost/NestMembers/Record
     * attributes — not fixable via PIT config). Otherwise fully covered by {@code DenialIdentitySpec}
     * and exercised across the mapper and merge specs.
     */
    @DoNotMutate
    public static Denial unidentified(Finding finding) {
        return new Denial(finding, null);
    }

    /** The findings of {@code denials}, in order — the view every report surface renders. */
    public static List<Finding> findings(List<Denial> denials) {
        return denials.stream().map(Denial::finding).toList();
    }
}
