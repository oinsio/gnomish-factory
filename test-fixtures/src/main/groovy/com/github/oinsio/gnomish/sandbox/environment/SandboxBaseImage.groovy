package com.github.oinsio.gnomish.sandbox.environment

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The container E2E layer's sandbox base image: {@code sandbox-image/Dockerfile} built once per JVM
 * the way the publishing workflows build it — the context {@code sandbox-image/}, the named build
 * context {@code scripts} for the git install script, and one build argument per value of
 * {@code sandbox-image/image-contract.properties}. The one owner of that build in the test trees;
 * an E2E image that needs the factory's image contract builds {@code FROM} {@link #TAG}.
 *
 * <p>The contract build arguments are the lines {@code scripts/image-contract-build-args.sh} prints
 * for the source file — the one translation of the file into build arguments, which the release's
 * {@code image} job and the weekly rebuild run too (design D16 of add-sandbox-base-image) — and never
 * come from {@link ImageContract}: the factory's reader is the other half of the one-source claim
 * (scenario {@code sandbox/sandbox-base-image / One edit changes both sides}), so a spec comparing the
 * built label with {@link ImageContract#current()} compares the two halves rather than one reader
 * with itself. The fixture holds no key or argument name of its own.
 *
 * <p>The repository root comes from the {@code repoRoot} system property the consuming module's
 * test task sets. Docker's layer cache keeps a rebuild in a later JVM to seconds.
 *
 * <p>Implements FR10 of add-sandbox-base-image (the base fixture of task 4.2).
 */
final class SandboxBaseImage {

    /** The local tag of the built base; never pushed. */
    static final String TAG = 'gnomish-sandbox-base:e2e'

    /** The {@code org.opencontainers.image.version} the fixture builds with. */
    static final String IMAGE_VERSION = '0.0.0-e2e'

    /** The {@code org.opencontainers.image.revision} the fixture builds with. */
    static final String IMAGE_REVISION = 'e2e-fixture'

    /** The one translation of the contract file into build arguments, from the repository root. */
    static final String BUILD_ARGS_SCRIPT = 'scripts/image-contract-build-args.sh'

    /** The contract file, from the repository root. */
    static final String CONTRACT_SOURCE = 'sandbox-image/image-contract.properties'

    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(60)

    private static boolean built

    private SandboxBaseImage() {}

    /** Builds the base if this JVM has not yet; returns {@link #TAG}. */
    static synchronized String ensureBuilt() {
        if (!built) {
            Path root = repoRoot()
            List<String> args = [
                'build',
                '-t',
                TAG,
                '--build-context',
                "scripts=${root.resolve('scripts')}".toString()
            ]
            contractArguments(root).each { line ->
                args += ['--build-arg', line]
            }
            args += [
                '--build-arg',
                "IMAGE_VERSION=${IMAGE_VERSION}".toString(),
                '--build-arg',
                "IMAGE_REVISION=${IMAGE_REVISION}".toString(),
                root.resolve('sandbox-image').toString()
            ]
            TestDockerCommand.succeed(args)
            built = true
        }
        TAG
    }

    /**
     * Builds {@code tag} from the {@code dockerfile} in {@code context}, a Dockerfile that starts
     * {@code ARG BASE} and {@code FROM ${BASE}}, over the base this fixture built; {@code dockerfile}
     * is a path inside {@code context}, or an absolute one.
     *
     * @return the build's result, failed or not: a spec may assert that a build fails
     */
    static TestDockerCommand.Result buildFrom(String tag, Path context, String dockerfile) {
        String base = ensureBuilt()
        TestDockerCommand.run([
            'build',
            '-t',
            tag,
            '--build-arg',
            "BASE=${base}".toString(),
            '-f',
            context.resolve(dockerfile).toString(),
            context.toString()
        ])
    }

    /**
     * Builds {@code tag} over the base from {@code dockerfile}, the text of a Dockerfile whose stage
     * for the box starts {@code FROM ${BASE}}; the fixture puts {@code ARG BASE} in front of it, so a
     * stage before that one (a JDK or a tool to copy from) may use the argument too. The text is
     * written to a file outside {@code context}, never piped into {@code docker}. A child image may
     * not download in a stage {@code FROM} the base (design D13 of add-sandbox-base-image): the
     * base's proxy names the guard, which does not exist at build time.
     */
    static void buildFromText(String tag, Path context, String dockerfile) {
        assert dockerfile.contains('FROM ${BASE}'): "the Dockerfile of ${tag} does not build FROM the base"
        Path file = Files.createTempFile('gnomish-dockerfile-', '')
        try {
            Files.writeString(file, 'ARG BASE\n' + dockerfile.stripIndent())
            def result = buildFrom(tag, context, file.toString())
            assert result.exitCode == 0: "docker build of ${tag} failed:\n${result.output}"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * The contract build arguments, one {@code NAME=value} line each, as {@link #BUILD_ARGS_SCRIPT}
     * prints them for {@link #CONTRACT_SOURCE}. Both paths are absolute from {@code root}, since a
     * test JVM's working directory is its module; the script runs through
     * {@link TestDockerCommand#command}, the launch every docker call of this fixture takes, and a
     * refusal is an assertion carrying what the script printed. The script prints its refusals on
     * standard output too, so the exit code alone tells a list from a refusal.
     */
    private static List<String> contractArguments(Path root) {
        def run = TestDockerCommand.command([
            'sh',
            root.resolve(BUILD_ARGS_SCRIPT).toString(),
            root.resolve(CONTRACT_SOURCE).toString()
        ], SCRIPT_TIMEOUT)
        List<String> lines = run.output.readLines()
        assert run.exitCode == 0 && !lines.isEmpty(): "${BUILD_ARGS_SCRIPT} exited ${run.exitCode}:\n${run.output}"
        lines
    }

    /** The repository root, from the {@code repoRoot} system property of the test task. */
    static Path repoRoot() {
        String property = System.getProperty('repoRoot')
        assert property: 'repoRoot is not set; the consuming module\'s test task must set it to the repository root'
        Path root = Path.of(property)
        assert Files.isRegularFile(root.resolve('sandbox-image/Dockerfile')): "repoRoot ${root} holds no sandbox-image/Dockerfile"
        root
    }
}
