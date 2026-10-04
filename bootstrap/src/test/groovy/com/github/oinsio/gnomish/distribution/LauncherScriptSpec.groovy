package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The committed launcher {@code bootstrap/src/dist/bin/gnomish} (FR5, NFR-P1, UX2 of
 * add-release-pipeline; design D4), run under {@code sh} from a throwaway distribution layout.
 *
 * <p>The {@code java} it finds is a fake: a shell script that answers the launcher's one probe as
 * a Java of the version the feature chose, and otherwise prints, one per line, the arguments the
 * launcher started it with — so "the factory receives exactly these arguments" is read off the
 * real {@code exec}, not inferred from the script's text. Every call is appended to a call log,
 * which is how the one-probe budget of NFR-P1 is counted.
 */
class LauncherScriptSpec extends Specification {

    private static final Path LAUNCHER = RepoSourceTree.repoRoot().resolve('bootstrap/src/dist/bin/gnomish')

    /** The external tools the launcher calls; everything else it uses is a shell builtin. */
    private static final List<String> TOOLS = ['dirname', 'readlink', 'sed']

    @TempDir
    Path tmp

    Path dist
    Path jar
    Path calls
    /** A folder holding only {@link #TOOLS}, so no host {@code java} is ever on the PATH. */
    String systemPath

    def setup() {
        dist = tmp.resolve('gnomish-1.2.3')
        Files.createDirectories(dist.resolve('bin'))
        Files.copy(LAUNCHER, dist.resolve('bin/gnomish'))
        jar = Files.createDirectories(dist.resolve('lib')).resolve('gnomish-1.2.3.jar')
        Files.writeString(jar, 'not a real jar: the fake java never opens it')
        calls = tmp.resolve('java-calls.log')
        def tools = Files.createDirectories(tmp.resolve('tools'))
        TOOLS.each { tool ->
            def real = ['/usr/bin', '/bin'].collect {
                Path.of(it, tool)
            }.find {
                Files.isExecutable(it)
            }
            assert real != null: "no ${tool} in /usr/bin or /bin"
            Files.createSymbolicLink(tools.resolve(tool), real)
        }
        systemPath = tools.toString()
    }

    // FR5: the committed file is what the distribution ships with mode 0755
    def "FR5: the committed launcher is an executable POSIX sh script"() {
        expect:
        Files.isExecutable(LAUNCHER)
        Files.readAllLines(LAUNCHER).first() == '#!/bin/sh'
    }

    // FR5, UX2: "Old Java is refused plainly"
    def "UX2: Java #feature on PATH is refused, naming the binary found and its version"() {
        given:
        def java = fakeJava('path-java', feature, version)

        when:
        def run = launch(['take'], [PATH: "${java.parent}:${systemPath}".toString()])

        then:
        run.exit != 0
        run.stderr == "gnomish: Java 25 or newer is required; found ${version} at ${java}" +
                ' (set JAVA_HOME to a Java 25 installation)\n'
        run.stdout.isEmpty()

        and: 'the factory never started: the probe was the only call'
        callLog() == ['probe']

        where:
        feature | version
        '21' | '21.0.4'
        '1.8' | '1.8.0_402'
    }

    // FR5: "Newer Java is accepted" — the floor itself and a version above it
    def "FR5: Java #feature is accepted and the factory is started"() {
        given:
        def java = fakeJava('path-java', feature, "${feature}.0.1")

        when:
        def run = launch(['--version'], [PATH: "${java.parent}:${systemPath}".toString()])

        then:
        run.exit == 0
        run.stdout.readLines() == [
            '-jar',
            jar.toRealPath().toString(),
            '--version'
        ]

        where:
        feature << ['25', '26']
    }

    // FR5: "Arguments pass through unchanged" — no factory option injected, nothing re-split
    def "FR5: every argument reaches the factory unchanged and in order, and no option is added"() {
        given:
        def java = fakeJava('path-java', '25', '25.0.1')
        def arguments = [
            'status',
            '--dir=/srv/widgets',
            'github:acme/widgets#7',
            'two words',
            '*',
            ''
        ]

        when:
        def run = launch(arguments, [PATH: "${java.parent}:${systemPath}".toString()])

        then:
        run.exit == 0
        run.stdout.split('\n', -1).toList().dropRight(1) == [
            '-jar',
            jar.toRealPath().toString()
        ] + arguments
    }

    // FR5: GNOMISH_JAVA_OPTS reaches the JVM, split on whitespace, before -jar and never globbed
    def "FR5: GNOMISH_JAVA_OPTS are JVM options placed before -jar"() {
        given:
        def java = fakeJava('path-java', '25', '25.0.1')

        when:
        def run = launch(['take'], [
            PATH: "${java.parent}:${systemPath}".toString(),
            GNOMISH_JAVA_OPTS: '-Xmx2g  -Dgnomish.probe=*'])

        then:
        run.stdout.readLines() == [
            '-Xmx2g',
            '-Dgnomish.probe=*',
            '-jar',
            jar.toRealPath().toString(),
            'take'
        ]
    }

    // FR5: JAVA_HOME wins over PATH
    def "FR5: JAVA_HOME is preferred to the java on PATH"() {
        given: 'an old java on PATH and a current one under JAVA_HOME'
        def onPath = fakeJava('path-java', '21', '21.0.4')
        def home = fakeJava('jdk/bin', '25', '25.0.1').parent.parent

        when:
        def run = launch(['take'], [PATH: "${onPath.parent}:${systemPath}".toString(), JAVA_HOME: home.toString()])

        then:
        run.exit == 0
        run.stdout.readLines() == [
            '-jar',
            jar.toRealPath().toString(),
            'take'
        ]
    }

    // NFR-P1: the Java check costs one short JVM start, then the launcher execs the factory
    def "NFR-P1: the launcher starts java exactly twice — one probe, then the factory"() {
        given:
        def java = fakeJava('path-java', '25', '25.0.1')

        when:
        launch(['take'], [PATH: "${java.parent}:${systemPath}".toString()])

        then:
        callLog() == ['probe', 'run']
    }

    // FR5: no java at all is a refusal, not a shell error
    def "FR5: with no java on PATH and no JAVA_HOME the launcher refuses and says how to fix it"() {
        when:
        def run = launch(['take'], [PATH: systemPath])

        then:
        run.exit != 0
        run.stderr == 'gnomish: Java 25 or newer is required; no java found at PATH' +
                ' (set JAVA_HOME to a Java 25 installation)\n'
    }

    // FR5: the jar is found from the script's real location, so a link on PATH still works
    def "FR5: a symlink to the launcher still finds the distribution's jar"() {
        given:
        def java = fakeJava('path-java', '25', '25.0.1')
        def link = Files.createDirectories(tmp.resolve('links')).resolve('gnomish')
        Files.createSymbolicLink(link, dist.resolve('bin/gnomish'))

        when:
        def run = run(link, ['take'], [PATH: "${java.parent}:${systemPath}".toString()])

        then:
        run.exit == 0
        run.stdout.readLines() == [
            '-jar',
            jar.toRealPath().toString(),
            'take'
        ]
    }

    // FR5: one jar, or a refusal naming the folder — never a guess between two
    def "FR5: a lib folder holding #count jars is refused"() {
        given:
        def java = fakeJava('path-java', '25', '25.0.1')
        Files.delete(jar)
        count.times {
            Files.writeString(dist.resolve("lib/gnomish-${it}.jar"), '')
        }

        when:
        def run = launch(['take'], [PATH: "${java.parent}:${systemPath}".toString()])

        then:
        run.exit != 0
        run.stderr.contains(dist.toRealPath().resolve('lib').toString())
        callLog() == []

        where:
        count << [0, 2]
    }

    /**
     * A fake {@code java} at {@code tmp/<dir>/java}: answers {@code -XshowSettings:properties
     * -version} like a JDK of {@code specification} / {@code version} would (on stderr), and
     * otherwise prints its arguments one per line. Each call appends {@code probe} or {@code run}.
     */
    private Path fakeJava(String dir, String specification, String version) {
        def java = Files.createDirectories(tmp.resolve(dir)).resolve('java')
        Files.writeString(java, """\
#!/bin/sh
if [ "\$1" = '-XshowSettings:properties' ]; then
    echo probe >> '${calls}'
    {
        echo 'Property settings:'
        echo '    java.specification.version = ${specification}'
        echo '    java.version = ${version}'
        echo '    java.version.date = 2026-01-20'
        echo ''
        echo 'openjdk version "${version}"'
    } >&2
    exit 0
fi
echo run >> '${calls}'
for argument in "\$@"; do
    printf '%s\\n' "\$argument"
done
""")
        java.toFile().setExecutable(true)
        java
    }

    private List<String> callLog() {
        Files.exists(calls) ? Files.readAllLines(calls) : []
    }

    private Map launch(List<String> arguments, Map<String, String> environment) {
        run(dist.resolve('bin/gnomish'), arguments, environment)
    }

    private static Map run(Path launcher, List<String> arguments, Map<String, String> environment) {
        def builder = new ProcessBuilder(['sh', launcher.toString()] + arguments)
        builder.environment().clear()
        builder.environment().putAll(environment)
        def process = builder.start()
        def stdout = process.inputStream.text
        def stderr = process.errorStream.text
        [exit: process.waitFor(), stdout: stdout, stderr: stderr]
    }
}
