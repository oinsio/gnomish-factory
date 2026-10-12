package com.github.oinsio.gnomish.build

/**
 * The existing scenario a dangling marker most likely meant, so the finding can name it (FR5 of
 * tighten-spec-contract): the candidate at the smallest edit distance from what the marker says.
 * A plain Levenshtein distance over the whole {@code <spec-id> / <title>} key; it runs only for
 * dangling markers, which are few, so its quadratic cost does not reach the gate's wall time.
 *
 * <p>Implements FR5 and NFR-O1 of tighten-spec-contract.
 */
final class NearestTitle {

    private NearestTitle() {
    }

    /** The candidate nearest {@code wanted}, or null when there is none. */
    static String of(String wanted, Collection<String> candidates) {
        candidates.min { String candidate -> distance(wanted, candidate) }
    }

    static int distance(String a, String b) {
        int[] previous = (0..b.length()) as int[]
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1]
            current[0] = i
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1)
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1))
            }
            previous = current
        }
        previous[b.length()]
    }
}
