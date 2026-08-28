package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 * Atomic-write boundary gate (FR5, design D10 of harden-task-branch-contract): the
 * temp-file-plus-rename discipline lives in exactly one place — the dependency-free
 * {@code :atomicfile} leaf — and every host-side writer of a factory-owned file goes
 * through it. Two rules, one per half of that claim: nobody outside the leaf names
 * {@link java.nio.file.StandardCopyOption} (a private copy of the discipline has to,
 * for {@code ATOMIC_MOVE}), and each named {@code .gnomish-task/} writer really
 * depends on {@code AtomicFileWriter}.
 *
 * <p>The container-side persisters are deliberately absent from the second rule: they
 * reach durability at commit granularity, per the per-medium table of
 * {@code docs/adr/0003-crash-consistency.md}, and consume no host filesystem writer.
 *
 * <p>FR5: no reader of a factory-owned file ever observes a partial write.
 */
class AtomicWriteBoundarySpec extends Specification {

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    def "FR5: the atomic-rename discipline exists only in the atomicfile leaf"() {
        given: 'the rule reserving StandardCopyOption to the shared writer'
        def rule = noClasses()
                .that().resideOutsideOfPackage('com.github.oinsio.gnomish.atomicfile')
                .should().dependOnClassesThat()
                .haveFullyQualifiedName('java.nio.file.StandardCopyOption')

        expect: 'no module keeps a private temp-file-plus-rename copy'
        rule.check(productionClasses)
    }

    def "FR5: every host-side factory-file writer goes through the shared writer"() {
        given: 'the writers named by design D10 — the .gnomish-task/ four plus the dashboard and snapshot writers'
        def rule = classes()
                .that().haveFullyQualifiedName('com.github.oinsio.gnomish.adapter.git.GitTaskRepository')
                .or().haveFullyQualifiedName('com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence')
                .or().haveFullyQualifiedName('com.github.oinsio.gnomish.adapter.git.TerminalWriteMarker')
                .or().haveFullyQualifiedName('com.github.oinsio.gnomish.adapter.git.state.TraceLineWriter')
                .or().haveFullyQualifiedName('com.github.oinsio.gnomish.serveobservability.writer.SnapshotWriteCycle')
                .or().haveFullyQualifiedName('com.github.oinsio.gnomish.dashboard.DashboardWatchLoop')
                .should().dependOnClassesThat()
                .haveFullyQualifiedName('com.github.oinsio.gnomish.atomicfile.AtomicFileWriter')

        expect: 'each one writes its file through the shared atomic writer'
        rule.check(productionClasses)
    }
}
