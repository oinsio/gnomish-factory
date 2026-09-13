package com.github.oinsio.gnomish.adapter.law

/**
 * Shared test helper for {@link GitObjectsLawSourceSpec} and {@link LawSourceContractSpec}: both
 * assert directory listings by bare entry name rather than by {@link LawEntry} identity.
 */
final class LawEntryAssertions {

    private LawEntryAssertions() {
    }

    static Map<String, LawEntry.Kind> byName(List<LawEntry> entries) {
        entries.collectEntries { [it.name(), it.kind()] }
    }
}
