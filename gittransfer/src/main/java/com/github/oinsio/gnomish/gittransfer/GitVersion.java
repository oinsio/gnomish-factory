package com.github.oinsio.gnomish.gittransfer;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An installed git's version as three numbers, with the floor the factory refuses to run below.
 * The floor is {@code 2.45.1}: the release that closed the local-clone class of CVE-2024-32002 and
 * its siblings, whose protections the seed clone relies on — and every flag the owner uses
 * ({@code --no-write-fetch-head} 2.29, {@code --end-of-options} 2.24, {@code GIT_CONFIG_GLOBAL}
 * 2.32) sits below it, so one floor covers the whole argv (design D8 of own-git-transfer-argv).
 *
 * <p>{@link #parse} is lenient on purpose: it reads the three numbers after {@code git version}
 * and ignores whatever follows — {@code (Apple Git-200)}, {@code .windows.1}, {@code -rc1} — so a
 * vendor build is not refused for its suffix, and a missing patch number reads as zero. A line
 * that carries no version at all yields nothing, which the startup check refuses like a version
 * below the floor (NFR-R2). The line is a subprocess's stdout; the adapter that captures it holds
 * the parser warrant, this type only converts.
 *
 * <p>Implements FR10, NFR-R2 of own-git-transfer-argv.
 *
 * @param major the first number
 * @param minor the second number
 * @param patch the third number, zero when absent
 */
public record GitVersion(int major, int minor, int patch) implements Comparable<GitVersion> {

    /** The lowest git the factory runs with. */
    public static final GitVersion FLOOR = new GitVersion(2, 45, 1);

    /** Why the floor is where it is, phrased for the startup refusal. */
    public static final String FLOOR_REASON =
            "the seed clone relies on git's local-clone hook protections (2.39.4/2.45.1)";

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
