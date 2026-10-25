package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.sandbox.environment.ImageContract
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR6, M2 of add-sandbox-base-image (design D2, single-owner row 1): the image contract — contract
 * version, guard alias, guard port — has one source, {@code sandbox-image/image-contract.properties}.
 * The factory reads it as the class-path resource {@code :sandbox:docker} copies at build time; the
 * image build reads it into build arguments. This gate pins both halves of "one source": the copy is
 * the file, and neither the box module nor any Dockerfile spells a value by hand
 * ({@link ImageContractSpelling} holds the rule, with the values read from the file).
 */
class ImageContractSourceSpec extends Specification {

    private static final String SOURCE = 'sandbox-image/image-contract.properties'
    private static final String BOX_MODULE = 'sandbox/docker/src/main'
    private static final String RECIPE = 'docs/examples/sandbox-image/Dockerfile'

    /** The Dockerfiles the walk must reach; a new one is added here with the file. */
    private static final List<String> KNOWN_DOCKERFILES = [
        RECIPE,
        'adapters/git/src/test/resources/jvm-proxy-egress/Dockerfile',
        'bootstrap/src/test/resources/sandbox-base-image/Dockerfile.loosened',
        'bootstrap/src/test/resources/sandbox-base-image/Dockerfile.maven',
        'bootstrap/src/test/resources/sandbox-base-image/Dockerfile.operator',
        'sandbox-image/Dockerfile',
        'scripts/linux-runner/Dockerfile'
    ]

    /** The consumers the box-module scan must reach: the record and the guard's commands. */
    private static final List<String> KNOWN_BOX_FILES = [
        "$BOX_MODULE/java/com/github/oinsio/gnomish/sandbox/environment/ImageContract.java",
        "$BOX_MODULE/java/com/github/oinsio/gnomish/sandbox/environment/GuardCommands.java",
    ]*.toString()

    private static final Set<String> SKIPPED_DIRECTORIES = [
        '.git',
        '.gradle',
        '.idea',
        'build',
        'node_modules'
    ] as Set

    @Shared
    Properties source = load(root().resolve(SOURCE))

    // FR6: the factory requires what the file says — the resource it reads is the file, byte for
    //     byte, and the record it builds carries the file's values. The image half of the scenario
    //     (the base's label equals the file) is pinned where the base is built (task 2.2).
    // Scenario: sandbox/sandbox-base-image / One edit changes both sides
    def "FR6: the factory's contract resource is the source file and carries its values"() {
        given:
        def resource = ImageContract.classLoader.getResourceAsStream(ImageContract.RESOURCE)

        expect:
        resource != null
        resource.withCloseable {
            it.bytes
        } == Files.readAllBytes(root().resolve(SOURCE))
        ImageContract.current() == new ImageContract(
                source.getProperty('contract-version') as int,
                source.getProperty('guard-alias'),
                source.getProperty('guard-port') as int)
    }

    // FR6, M2: no file of the box module spells a contract value; every file is read, comments too.
    def "FR6, M2: no file under the box module spells the alias, the port or a contract label value"() {
        given:
        def module = root().resolve(BOX_MODULE)

        when:
        def files = walk(module) { true }
        def reached = files*.relative
        def found = offenders(files)

        then: 'the scan read every regular file under the module, the known consumers among them'
        reached.size() == regularFileCount(module)
        reached.containsAll(KNOWN_BOX_FILES)

        and:
        found == []
    }

    // FR6, M2: the walk over the repository found every known Dockerfile, so a clean result below is
    //     a judgement over the real set, not over a walk that stopped early.
    def "FR6, M2: the Dockerfile walk reaches every known Dockerfile"() {
        expect:
        dockerfiles()*.relative.containsAll(KNOWN_DOCKERFILES)
    }

    // FR6, M2: no Dockerfile — the base, the reference recipe, the test images — spells the values.
    def "FR6, M2: #dockerfile spells no alias, port or contract label value"() {
        when:
        def found = offenders(dockerfiles().findAll {
            it.relative == dockerfile
        })

        then:
        found == []

        where:
        dockerfile << dockerfiles()*.relative
    }

    private List<String> offenders(List<Map> files) {
        def alias = source.getProperty('guard-alias')
        def port = source.getProperty('guard-port')
        files.collect { file ->
            def kinds = ImageContractSpelling.spelled(file.text as String, alias, port)
            kinds ? "${file.relative}: ${kinds.join(', ')}".toString() : null
        }.findAll().sort()
    }

    private static List<Map> dockerfiles() {
        walk(root()) { String name ->
            name == 'Dockerfile' || name.startsWith('Dockerfile.') || name.toLowerCase().endsWith('.dockerfile')
            || name.startsWith('Containerfile')
        }
    }

    /** Every regular file under {@code start} whose name the filter accepts, read whole. */
    private static List<Map> walk(Path start, Closure<Boolean> nameFilter) {
        def found = []
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                    @Override
                    FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        dir != start && SKIPPED_DIRECTORIES.contains(dir.fileName.toString())
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE
                    }

                    @Override
                    FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.regularFile && nameFilter.call(file.fileName.toString())) {
                            found << [relative: root().relativize(file).toString(), text: Files.readString(file)]
                        }
                        FileVisitResult.CONTINUE
                    }
                })
        found.sort { it.relative }
    }

    /** An independent count of the regular files under {@code start}, to check the walk read them all. */
    private static long regularFileCount(Path start) {
        Files.walk(start).withCloseable { paths ->
            paths.filter {
                Files.isRegularFile(it)
            }.count()
        }
    }

    private static Path root() {
        RepoSourceTree.repoRoot()
    }

    private static Properties load(Path file) {
        def properties = new Properties()
        Files.newInputStream(file).withCloseable { properties.load(it) }
        properties
    }
}
