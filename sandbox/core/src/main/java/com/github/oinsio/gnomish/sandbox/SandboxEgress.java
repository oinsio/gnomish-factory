package com.github.oinsio.gnomish.sandbox;

import com.github.oinsio.gnomish.httpegress.EgressEntries;
import java.util.List;

/**
 * The operator's grant to every box of the installation: the {@code sandbox} scope of the operator
 * egress block, {@code factory.egress.sandbox} (FR17, design D16 of
 * migrate-verify-to-executor-contract; FR7 of add-sandbox-core). The box guard admits these names
 * beside the hosts a law grants its {@code program} executors, and the environment self-check dials
 * one of them.
 *
 * <p>Every entry is held to the {@code sandbox} subset of the one egress grammar ({@link
 * EgressEntries#isSandboxEntry}) — an exact host, an IP literal or a {@code *.suffix} wildcard —
 * because the guard matches names only and resolves nothing: a CIDR block or a class word here would
 * be a grant the guard silently cannot apply. Startup reports such an entry as a located violation
 * of the operator file first; this value refuses it again, so no path that bypassed the file check
 * hands the guard an entry it cannot apply. Entries are kept normalized, in operator order.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract; FR7 of add-sandbox-core.
 *
 * @param entries the normalized entries; never null, empty for a default-deny box
 */
public record SandboxEgress(List<String> entries) {

    /** The operator key these entries are read from. */
    public static final String KEY = "factory.egress.sandbox";

    public SandboxEgress {
        entries = normalized(entries);
    }

    /** The default-deny grant: nothing. */
    public static SandboxEgress none() {
        return new SandboxEgress(List.of());
    }

    /**
     * Each entry normalized; one outside the {@code sandbox} subset is refused naming the key.
     * Outside the canonical constructor so the judgement stays in the mutation scope (PIT's record
     * filter suppresses mutations inside a record's canonical constructor).
     */
    private static List<String> normalized(List<String> entries) {
        for (String entry : entries) {
            if (!EgressEntries.isSandboxEntry(entry)) {
                throw new IllegalArgumentException(("%s entry '%s' is not an exact host, an IP literal or a *.suffix"
                                + " wildcard; the box guard matches names only, so a CIDR block or a class word"
                                + " cannot be applied there")
                        .formatted(KEY, entry));
            }
        }
        return entries.stream().map(EgressEntries::normalized).toList();
    }
}
