package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.httpegress.EgressAllowlist;
import com.github.oinsio.gnomish.httpegress.EgressEntries;
import com.github.oinsio.gnomish.sandbox.SandboxEgress;
import java.util.Optional;

/**
 * The judgement of one entry of the operator egress block at startup (FR17, design D16 of
 * migrate-verify-to-executor-contract), so a wrong entry is a violation located at the file and
 * line that set it rather than a failure of the first box or call that reads it. Every entry is
 * read by the one egress grammar, {@link EgressEntries}: the {@value SandboxEgress#KEY} scope by
 * its {@code sandbox} subset — the box guard matches names only, so a CIDR block or a class word
 * there is a grant the guard cannot apply — and the {@value EgressAllowlist#IN_PROCESS_KEY} scope
 * by the full grammar.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract.
 */
final class EgressScopes {

    private EgressScopes() {}

    /** Why {@code entry} is refused, and the fix — one violation's two closing parts. */
    record Refusal(String what, String why, String fix) {}

    /**
     * Judges one entry set under {@code key}.
     *
     * @param key the bound key the entry was set under, e.g. {@code factory.egress.sandbox}
     * @param entry the entry as written
     * @return the refusal, or empty for an entry the scope accepts or a key that is no egress scope
     */
    static Optional<Refusal> refusal(String key, String entry) {
        if (SandboxEgress.KEY.equals(key) && !EgressEntries.isSandboxEntry(entry)) {
            return Optional.of(new Refusal(
                    "%s entry '%s' cannot be applied by the box guard".formatted(key, entry),
                    "the box guard matches names only — an exact host, an IP literal or a "
                            + EgressEntries.WILDCARD_PREFIX + "suffix wildcard, never a CIDR block or an address-class"
                            + " word",
                    "name the hosts the box may reach, or move the entry to " + EgressAllowlist.IN_PROCESS_KEY
                            + " if the factory process is the one that calls them"));
        }
        if (EgressAllowlist.IN_PROCESS_KEY.equals(key) && !EgressEntries.isEntry(entry)) {
            return Optional.of(new Refusal(
                    "%s entry '%s' is not an egress entry".formatted(key, entry),
                    "an entry is an exact host, a " + EgressEntries.WILDCARD_PREFIX + "suffix wildcard, an IP literal,"
                            + " a CIDR block or the class word " + EgressEntries.LOOPBACK + " or "
                            + EgressEntries.PRIVATE + " — never a URL, a scheme or a port",
                    "correct the entry"));
        }
        return Optional.empty();
    }
}
