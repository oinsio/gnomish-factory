package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import spock.lang.Specification

/**
 * FR6, M4 of add-release-pipeline (design D2, single-owner row): the product version reaches
 * runtime through one reader, {@code FactoryVersion}, which reads the boot jar's build info. No
 * production source asks a {@code Package} for its {@code Implementation-Version}: that answer
 * belongs to whichever nested jar holds the calling class, which is how the snapshot and ledger
 * once reported {@code :application}'s module placeholder as the factory's version.
 *
 * <p>Exempt by design: the {@code Implementation-Version} attribute {@code library-conventions}
 * stamps on each internal module's manifest stays as a module attribute — it is written, never
 * read. The scan asserts it reached the whole production tree, the shape {@link
 * InstanceIdMintBoundarySpec} uses.
 */
class FactoryVersionBoundarySpec extends Specification {

    def "M4: no production source reads a manifest Implementation-Version"() {
        given: 'every production source, comments stripped'
        def sources = RepoSourceTree.productionSources()
        def readers = sources.findAll {
            readsManifestVersion(RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        expect: 'the scan reached the whole production tree'
        sources.size() > RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'nothing reads the manifest version'
        readers.isEmpty()
    }

    def "the detector finds a seeded read and leaves a commented one alone: #shape"() {
        expect:
        readsManifestVersion(RepoSourceTree.codeOnly(source)) == detected

        where:
        shape | source || detected
        'a package read' | 'String v = Foo.class.getPackage().getImplementationVersion();' || true
        'a spaced call' | 'String v = pkg . getImplementationVersion ();' || true
        'a trailing comment' | 'String v = FactoryVersion.current().value(); // not getImplementationVersion()' || false
        'the owner call' | 'String v = FactoryVersion.current().value();' || false
    }

    // NG3, scenario "Plugin contract is untouched": `ReleaseVersionSpec` proves the plugin reaches
    // only the project applying it, on a miniature build; this pins which real project that is.
    // `verifyPublishedApiVersion` cannot stand in: `0.0.0-dev` is a valid semver it accepts.
    def "NG3: only :bootstrap applies the product version"() {
        given: 'every build script of the main build, the convention plugins excluded'
        def scripts = buildScripts()
        def appliers = scripts.findAll {
            it.text =~ /id\s+'product-version-conventions'/
        }
        .collect { RepoSourceTree.relative(it) }

        expect: 'the scan reached the published API module'
        scripts.collect {
            RepoSourceTree.relative(it)
        }.contains('gnomish-plugin-api/build.gradle')

        and: 'the product version is applied to the shipped executable alone'
        appliers == ['bootstrap/build.gradle']
    }

    /** Every {@code *.gradle} outside build output and outside the {@code build-logic} plugins. */
    private static List<File> buildScripts() {
        Files.walk(RepoSourceTree.repoRoot()).withCloseable { paths ->
            paths.filter {
                Files.isRegularFile(it) && it.fileName.toString().endsWith('.gradle')
            }
            .map { it.toFile() }
            .filter {
                def path = RepoSourceTree.relative(it)
                !path.startsWith('build-logic/') && !path.contains('/build/') && !path.startsWith('build/')
            }
            .toList()
        }
    }

    private static boolean readsManifestVersion(String code) {
        code =~ /\bgetImplementationVersion\s*\(/
    }
}
