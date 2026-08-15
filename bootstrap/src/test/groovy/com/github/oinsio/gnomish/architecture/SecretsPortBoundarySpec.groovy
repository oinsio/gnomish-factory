package com.github.oinsio.gnomish.architecture

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification

/**
 * Secrets boundary gate (NFR-S1, task 5.5 of split-into-modules). Two halves of one guarantee.
 *
 * <p>First: every adapter reaches a secret through the {@code SecretsProvider} port, never through
 * the env/file implementation behind it. Only the composition root may name that implementation —
 * choosing which secrets backend is installed is exactly what a composition root is for, and it is
 * the single place to change when a Vault- or OIDC-backed provider arrives. Before task 5.3 the two
 * github factories carried convenience constructors that reached for it themselves, which is both a
 * sibling-adapter edge and a second, invisible decision about where secrets come from.
 *
 * <p>Second: no credential name or value appears in the build metadata. Credentials are resolved by
 * name at runtime; a build file that so much as names one is either about to carry a value or is
 * teaching CI to inject one, and either way it is the wrong layer. Gitleaks scans the working tree
 * for secret-shaped strings in CI; this gate is narrower and deterministic — it fails the local
 * build the moment a credential name enters a build script, a lockfile or the version catalog.
 */
class SecretsPortBoundarySpec extends Specification {

    private static final String COMPOSITION_ROOT = 'com.github.oinsio.gnomish.app.ManualRunConfiguration'

    /** The credential names the adapters declare; each MUST stay absent from the build metadata. */
    private static final List<String> CREDENTIAL_NAMES = [
        GithubTrackerAdapterFactory.TOKEN_ENV_VAR,
        GithubCheckClientFactory.TOKEN_ENV_VAR
    ]

    /**
     * Whole-tree production bytecode, mirroring {@link TrackerPortBoundarySpec}: the composition
     * root sees every layer at once, which is the scope this rule wants. Jars are deliberately
     * INCLUDED — the modules the rule is about reach this classpath as jars, and excluding them
     * would leave the rule passing over this module's eleven classes alone. The coverage guard
     * below is what keeps that honest.
     */
    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    // The rule below is only meaningful if the classes it constrains were actually imported
    def "the import scope really spans the adapter modules"() {
        expect: 'both the secrets adapter and a consumer of the port are in scope'
        productionClasses.any { it.packageName.contains('.adapter.secrets') }
        productionClasses.any {
            it.packageName.contains('.adapter.tracker.github')
        }
    }

    // NFR-S1: adapters resolve secrets through the port; only the composition root binds the impl
    def "no class but the composition root depends on the secrets adapter"() {
        given: 'the boundary rule, exempting the one class whose job is to bind the port'
        def rule = noClasses()
                .that()
                .resideOutsideOfPackage('..adapter.secrets..')
                .and()
                .doNotHaveFullyQualifiedName(COMPOSITION_ROOT)
                .should()
                .dependOnClassesThat()
                .resideInAPackage('..adapter.secrets..')

        expect: 'the rule holds over the production classes (check throws on violation)'
        rule.check(productionClasses)
    }

    // NFR-S1: no credential name — and so no credential value — lives in any module's build metadata
    def "no credential name appears in the build metadata"() {
        given: 'every build script, lockfile, properties file and the version catalog'
        def offenders = buildMetadataFiles().findAll { file ->
            def text = file.text
            CREDENTIAL_NAMES.any { text.contains(it) }
        }

        expect: 'none of them names a credential'
        offenders.isEmpty()
    }

    private static List<File> buildMetadataFiles() {
        def root = Path.of(System.getProperty('repoRoot'))
        assert Files.isDirectory(root): 'repoRoot system property is not set (see bootstrap/build.gradle)'
        Files.walk(root).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .filter { path ->
                def name = path.fileName.toString()
                name.endsWith('.gradle') || name.endsWith('.lockfile') ||
                        name == 'gradle.properties' || name == 'libs.versions.toml'
            }
            .filter {
                !it.toString().contains("${File.separator}build${File.separator}")
            }
            .map { it.toFile() }
            .toList()
        }
    }
}
