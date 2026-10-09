package com.github.oinsio.gnomish.architecture

/**
 * The detector behind {@link ProcessEnvironmentOwnerSpec} (design D14 of
 * make-checkpoint-gate-durable): finds every write into a child process's environment map and
 * says whether the same method cleared that map first.
 *
 * <p><b>A write</b> is a mutating map call ({@code put}, {@code putAll}, {@code putIfAbsent},
 * {@code merge}, {@code compute*}, {@code replace*}), a subscript assignment, or a Groovy property
 * assignment, on either {@code .environment()} directly or a local variable bound to a
 * {@code .environment()} result or to {@code TestChildEnvironment.cleared(...)} — so renaming the
 * local does not slip past the gate. <b>A clearing step</b> is {@code .environment().clear()},
 * {@code clear()} on such a variable, or a call of {@code TestChildEnvironment.cleared(}, the
 * shared helper that clears and composes (task 9.1).
 *
 * <p><b>The method</b> is approximated by brace depth: a region opens where the depth leaves the
 * class body (1 → 2) and lasts until it returns. String and character literals are blanked before
 * braces are counted. A nested class's methods therefore share one region — a looser rule there,
 * never a false offender.
 *
 * <p><b>Limits, stated rather than hidden.</b> The scan is textual: an environment map handed to
 * another method as a parameter, a write inside {@code environment().with { put(...) }}, or a map
 * reached through a field is not seen. And a spawn that writes nothing inherits without being a
 * write at all — that door is closed one layer up, by the build stripping {@code GNOMISH_*} from
 * the test JVM (task 9.2), which {@link TestEnvironmentHygieneSpec} asserts.
 *
 * <p>FR22, M10 of make-checkpoint-gate-durable.
 */
final class ProcessEnvironmentRule {

    private static final String MUTATOR =
    /\.(?:put|putAll|putIfAbsent|merge|compute|computeIfAbsent|computeIfPresent|replace|replaceAll)\s*\(/
    private static final String ASSIGNMENT = /\s*=(?!=)/
    private static final String SUBSCRIPT = /\[[^\]]*\]/
    private static final String HELPER = /\bTestChildEnvironment\s*\.\s*cleared\s*\(/
    private static final String DIRECT_ENV = /\.environment\(\)/
    private static final String BINDING = /\b(\w+)\s*=\s*(?:[\w.()]*\.environment\(\)\s*;?\s*$|TestChildEnvironment\s*\.\s*cleared\s*\()/

    private ProcessEnvironmentRule() {}

    /** One write into a child environment map: its 1-based line, and whether its method cleared first. */
    static record Write(int line, boolean cleared) {}

    /** Every environment write in {@code code} (comments already stripped), in source order. */
    static List<Write> writes(String code) {
        List<Write> found = []
        Set<String> maps = [] as Set
        boolean cleared = false
        int depth = 0
        code.split('\n', -1).eachWithIndex { String line, int index ->
            String bare = withoutLiterals(line)
            int peak = depth + Math.max(0, peakRise(bare))
            if (depth < 2 && peak >= 2) {
                maps = [] as Set
                cleared = false
            }
            if (depth >= 2 || peak >= 2) {
                def binding = line =~ BINDING
                if (binding.find()) {
                    maps << binding.group(1)
                }
                cleared = cleared || clears(line, maps)
                if (isWrite(line, maps)) {
                    found << new Write(index + 1, cleared)
                }
            }
            depth += bare.count('{') - bare.count('}')
        }
        found
    }

    private static boolean clears(String line, Set<String> maps) {
        line =~ HELPER || line =~ (DIRECT_ENV + /\s*\.clear\(\)/) || maps.any {
            line =~ (/\b/ + it + /\b\s*\.clear\(\)/)
        }
    }

    private static boolean isWrite(String line, Set<String> maps) {
        writesThrough(line, DIRECT_ENV + /\s*/) || writesThrough(line, HELPER + /[^)]*\)\s*/) || maps.any { String map ->
            writesThrough(line, /\b/ + map + /\b\s*/) || line =~ (/\b/ + map + /\b\s*\.\w+/ + ASSIGNMENT)
        }
    }

    /** A mutating call or a subscript assignment on whatever {@code head} matches. */
    private static boolean writesThrough(String line, String head) {
        line =~ (head + MUTATOR) || line =~ (head + SUBSCRIPT + ASSIGNMENT)
    }

    /** The highest depth reached inside the line, relative to its start. */
    private static int peakRise(String bare) {
        int level = 0
        int peak = 0
        bare.each { String c ->
            if (c == '{') {
                peak = Math.max(peak, ++level)
            } else if (c == '}') {
                level--
            }
        }
        peak
    }

    /** The line with every string and character literal's content removed, so its braces do not count. */
    static String withoutLiterals(String line) {
        line.replaceAll(/"(?:[^"\\]|\\.)*"/, '""').replaceAll(/'(?:[^'\\]|\\.)*'/, "''")
    }
}
