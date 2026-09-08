package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR8, FR11, D12 of add-base-ref-resolution: the one rule that decides a manual {@code gnomish
 * run}'s law source — a {@code --base} sends it to that ref's own commit, its absence keeps the
 * clone's working tree so a pipeline author's uncommitted edit still runs.
 */
class ManualRunLawBindingSpec extends Specification {

    private static final Path CLONE = Path.of('/clones/widgets')

    // FR8, UX3: without --base nothing was resolved, so the working tree stays the law — the one
    //     behaviour this change promises not to alter.
    def "FR8: a manual run without --base keeps the clone's working tree as its law"() {
        expect:
        ManualRunLawBinding.of(CLONE, null) == new LawBinding.WorkingTree(CLONE)
    }

    // FR11: with --base a ref was resolved, so the law comes from that ref's own commit — by fact,
    //     not by mode, exactly as take and serve bind theirs.
    def "FR11: a manual run with --base binds law at that ref"() {
        expect:
        ManualRunLawBinding.of(CLONE, 'v1.2.3') == new LawBinding.AtRevision(CLONE, 'v1.2.3')
    }
}
