package com.github.oinsio.gnomish.architecture

import java.util.regex.Pattern

/**
 * The rule of {@link ImageContractSourceSpec}, held apart from the gate that runs it: when a text
 * spells a value of the image contract (FR6, design D2 of add-sandbox-base-image) instead of taking
 * it from {@code sandbox-image/image-contract.properties}.
 *
 * <p>The values are never written here: the gate reads them from the source file and hands them in,
 * so raising the port or renaming the alias moves the rule with it. The whole text is judged,
 * comments included: a javadoc or a Dockerfile comment that names the alias or the port as a value
 * goes stale on the next edit of the source file exactly as a literal does.
 *
 * <ul>
 *   <li><b>Alias.</b> Spelled when the alias stands as a name of its own: not glued to a word
 *       character or {@code -} on either side (so the box names {@code gnomish-guard-<key>} and a
 *       longer word are not the alias), and not a path segment — one {@code /} before it that is not
 *       part of a URL's {@code //} (so the mount {@code /gnomish-guard} and
 *       {@code /gnomish-guard/allowlist.json} are paths, while {@code http://gnomish-guard:8080} is
 *       the alias). Quoted, assigned ({@code =gnomish-guard}), in a host:port pair or as a bare word
 *       in prose, it is a hit.
 *   <li><b>Port.</b> Spelled when the number stands alone: no word character or {@code .} before
 *       it, no word character or {@code .<digit>} after it ({@code :8080}, {@code =8080},
 *       {@code PROXY_PORT 8080}, {@code = 8080;}). In the scanned trees — the box module and the
 *       Dockerfiles — the guard port is the only meaning such a number has had; a future unrelated
 *       use of the same number is renamed to a constant of its own, which this rule does not judge.
 *   <li><b>Label.</b> {@code io.gnomish.image-contract} followed by {@code =} or {@code :} and a
 *       digit, quotes and spaces allowed between: the version written by hand, in a {@code LABEL}
 *       line, a {@code --label} argument or a map.
 * </ul>
 */
final class ImageContractSpelling {

    private static final Pattern LITERAL_LABEL = ~/io\.gnomish\.image-contract["']?\s*[=:]\s*["']?\d/

    private ImageContractSpelling() {
    }

    /** What the text spells, by kind ({@code alias}, {@code port}, {@code label}); empty when clean. */
    static List<String> spelled(String text, String alias, String port) {
        def kinds = []
        if (spellsAlias(text, alias)) {
            kinds << 'alias'
        }
        if (spellsPort(text, port)) {
            kinds << 'port'
        }
        if (LITERAL_LABEL.matcher(text).find()) {
            kinds << 'label'
        }
        kinds
    }

    static boolean spellsAlias(String text, String alias) {
        occurrences(text, alias).any { int i ->
            def before = charAt(text, i - 1)
            def after = charAt(text, i + alias.length())
            !nameChar(before) && !nameChar(after) && !pathSegment(text, i)
        }
    }

    static boolean spellsPort(String text, String port) {
        occurrences(text, port).any { int i ->
            def before = charAt(text, i - 1)
            int end = i + port.length()
            def after = charAt(text, end)
            !wordChar(before) && before != '.' && !wordChar(after)
                    && !(after == '.' && Character.isDigit(charAt(text, end + 1) as char))
        }
    }

    private static List<Integer> occurrences(String text, String value) {
        def found = []
        int i = text.indexOf(value)
        while (i >= 0) {
            found << i
            i = text.indexOf(value, i + 1)
        }
        found
    }

    /** One {@code /} before the value, not the second of a URL's {@code //}: a path segment. */
    private static boolean pathSegment(String text, int i) {
        charAt(text, i - 1) == '/' && charAt(text, i - 2) != '/'
    }

    private static boolean nameChar(String c) {
        wordChar(c) || c == '-'
    }

    private static boolean wordChar(String c) {
        c != ' ' && (Character.isLetterOrDigit(c as char) || c == '_')
    }

    /** The character at {@code i} as a one-letter string, or a blank outside the text. */
    private static String charAt(String text, int i) {
        i >= 0 && i <text.length() ? text[i] : ' '
    }
}
