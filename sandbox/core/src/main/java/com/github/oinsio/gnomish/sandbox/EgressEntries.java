package com.github.oinsio.gnomish.sandbox;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The grammar of one egress allowlist entry: an exact host name, an IP literal (v6 included) or a
 * {@code *.suffix} wildcard, judged after stripping surrounding blanks and folding to lower case;
 * nothing JSON-active. One owner for every reader of an entry: the egress guard renders the
 * operator's {@code factory.sandbox.egress-allowlist} through it, the {@code .gnomish/} loader
 * judges a law's {@code executors.<name>.egress} hosts with it, and the describe preflight judges
 * and compares an executor's describe-declared hosts with it, so a host the law accepts is a host
 * the guard can render and the law and the describe answer are compared in one spelling (FR7 of
 * add-sandbox-core; FR4, FR9 of define-executor-contract). It lives in this module, beside the
 * child-environment policy, because the preflight's layer reaches this module and not the docker
 * adapter (task 6.4 of define-executor-contract moved it here rather than add a second parser).
 *
 * <p>Implements FR7 of add-sandbox-core; FR4, FR9 of define-executor-contract.
 */
public final class EgressEntries {

    private static final Pattern ENTRY = Pattern.compile("(\\*\\.)?[A-Za-z0-9._:-]+");

    private EgressEntries() {}

    /**
     * Whether {@code entry} is an allowlist entry once normalized.
     *
     * @param entry the raw entry as written; never null
     * @return true when the normalized entry matches the grammar
     */
    public static boolean isEntry(String entry) {
        return ENTRY.matcher(normalized(entry)).matches();
    }

    /**
     * The entry as the guard matches it: surrounding blanks stripped, folded to lower case.
     *
     * @param entry the raw entry as written; never null
     * @return the normalized entry
     */
    public static String normalized(String entry) {
        return entry.strip().toLowerCase(Locale.ROOT);
    }
}
