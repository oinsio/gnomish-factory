package com.github.oinsio.gnomish.gittransfer;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An installed git's version as three numbers, with the two floors git is held to: the factory's,
 * which it refuses to run below, and the box's, which the seed clone's image must meet (design D11
 * of own-git-invocation-policy).
 *
 * <p>The factory's floor is {@code 2.56.0}: the first release whose event-level hook switch
 * ({@code hook.<event>.enabled}) is documented and verified, which the invocation policy needs to
 * neutralize hooks defined in the configuration — a policy naming a key the installed git ignores
 * neutralizes nothing (design D3, D11 of own-git-invocation-policy). It also covers the earlier
 * reasons, which are the box's floor below.
 *
 * <p>The box's floor stays {@code 2.51.0} until {@code add-sandbox-base-image} raises it with the
 * images and joins the two again; the box's git is outside the factory's trust domain, so no
 * factory policy runs there and it needs no event switch. Its reasons: the seed clone relies on the
 * protections that closed the local-clone class of CVE-2024-32002 and its siblings (2.39.4/2.45.1,
 * design D8 of own-git-transfer-argv). The factory clone is on the reftable ref backend, which takes
 * one lock for the whole ref store; git retries it only since 2.47, so below that two concurrent ref
 * writes to one clone — a slot's closing transaction against another slot's fetch, or a gnome's
 * commit in a sibling worktree — fail. And since 2.51 a fetch writes its ref updates as one
 * transaction, so the touchpoint fetch cannot leave the branch moved and the register not (design
 * D13 of tighten-spec-contract, as amended 2026-10-13). Every flag the transfer owner uses
 * ({@code --no-write-fetch-head} 2.29, {@code --end-of-options} 2.24, {@code GIT_CONFIG_GLOBAL}
 * 2.32, {@code --ref-format} 2.45) sits below both, so each floor covers the whole argv.
 *
 * <p>{@link #parse} is lenient on purpose: it reads the three numbers after {@code git version}
 * and ignores whatever follows — {@code (Apple Git-200)}, {@code .windows.1}, {@code -rc1} — so a
 * vendor build is not refused for its suffix, and a missing patch number reads as zero. A line
 * that carries no version at all yields nothing, which the startup check refuses like a version
 * below the floor (NFR-R2). The line is a subprocess's stdout; the adapter that captures it holds
 * the parser warrant, this type only converts.
 *
 * <p>Implements FR10, NFR-R2 of own-git-transfer-argv; NFR-S2 of tighten-spec-contract; FR10 of
 * own-git-invocation-policy.
 *
 * @param major the first number
 * @param minor the second number
 * @param patch the third number, zero when absent
 */
public record GitVersion(int major, int minor, int patch) implements Comparable<GitVersion> {

    /** The lowest git the factory runs with. */
    public static final GitVersion FLOOR = new GitVersion(2, 56, 0);

    /** Why the factory's floor is where it is, phrased for the startup refusal. */
    public static final String FLOOR_REASON =
            "the invocation policy disables hooks defined in the configuration by their event-level switch"
                    + " (hook.<event>.enabled, 2.56), the seed clone relies on git's local-clone hook protections"
                    + " (2.39.4/2.45.1), the factory clone's concurrent ref writes on reftable lock retry (2.47),"
                    + " and the touchpoint fetch on writing its ref updates in one transaction (2.51)";

    /** The lowest git a box's image may carry for the seed clone; below the factory's until the images move. */
    public static final GitVersion BOX_FLOOR = new GitVersion(2, 51, 0);

    /** Why the box's floor is where it is, phrased for the image refusal. */
    public static final String BOX_FLOOR_REASON =
            "the seed clone relies on git's local-clone hook protections (2.39.4/2.45.1), the factory"
                    + " clone's concurrent ref writes on reftable lock retry (2.47), and the touchpoint fetch"
                    + " on writing its ref updates in one transaction (2.51)";

    /** {@code git --version}'s one line: the prefix, then two or three dotted numbers. */
    private static final Pattern VERSION_LINE =
            Pattern.compile("^\\s*git version (\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:\\D.*)?$", Pattern.DOTALL);

    private static final Comparator<GitVersion> ORDER = Comparator.comparingInt(GitVersion::major)
            .thenComparingInt(GitVersion::minor)
            .thenComparingInt(GitVersion::patch);

    public GitVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException(
                    "a version component is never negative: " + major + "." + minor + "." + patch);
        }
    }

    /**
     * Reads {@code git --version}'s output.
     *
     * @param line the captured line, surrounding whitespace and vendor suffix tolerated; never null
     * @return the version, or empty when the line names none
     */
    public static Optional<GitVersion> parse(String line) {
        Objects.requireNonNull(line, "line");
        Matcher matcher = VERSION_LINE.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return Optional.of(
                new GitVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), patch));
    }

    /**
     * Whether this version is older than {@code floor}.
     *
     * @param floor the version to compare against; never null
     * @return true when this version sorts strictly before it
     */
    public boolean isBelow(GitVersion floor) {
        return compareTo(floor) < 0;
    }

    @Override
    public int compareTo(GitVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
