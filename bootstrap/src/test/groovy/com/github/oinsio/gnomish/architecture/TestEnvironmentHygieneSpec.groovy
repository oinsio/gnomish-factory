package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Design D14 of make-checkpoint-gate-durable (D7 enforcement row "test environment"): a test
 * process never inherits the operator's environment. Two layers, one feature each.
 *
 * <ul>
 *   <li><b>The build.</b> {@code TestEnvironmentHygiene} in {@code build-logic} strips every
 *       {@code GNOMISH_*} variable from the forked test JVM, so this JVM must see none — whatever
 *       the gnome or operator that started the build was holding.
 *   <li><b>The fixture box.</b> {@code LocalBoxEnvironment.exec} composes its child's environment
 *       through the production allowlist over a cleared one. A running JVM cannot change its own
 *       environment, so the planted variable rides a child JVM ({@link FixtureBoxRoundProcess})
 *       whose environment this spec composes: {@code GNOMISH_DECISION_FILE} names a file here, the
 *       child runs one {@code decision-needed} fake-agent round through the box, and the file must
 *       be byte-for-byte what it was. The defect this pins: the fake agent wrote its scenario's
 *       question into the decision file of the gnome round whose build ran the test.
 * </ul>
 *
 * <p>FR22, M10 of make-checkpoint-gate-durable.
 */
class TestEnvironmentHygieneSpec extends Specification implements BareGitRepoFixture {

    private static final String UNTOUCHED = '{"planted": "the operator round\'s own decision file"}'

    @TempDir
    Path tempDir

    // FR22, M10: the build strips GNOMISH_* from every forked test JVM (task 9.2)
    def "FR22, M10: the test JVM's environment holds no GNOMISH_* variable"() {
        expect:
        System.getenv().keySet().findAll { it.startsWith('GNOMISH_') }.isEmpty()
    }

    // FR22, M10: a GNOMISH_DECISION_FILE in the box's parent environment never reaches the fake
    //     agent (task 9.1; red with LocalBoxEnvironment.exec inheriting)
    def "FR22, M10: a fake-agent round through the fixture box leaves a planted GNOMISH_DECISION_FILE untouched"() {
        given: 'a factory clone with one commit on its branch'
        Path clone = initWorkingRepo(tempDir, 'clone')
        commit(clone, 'seed.txt', 'seed')
        String branch = gitOutput(clone, 'rev-parse', '--abbrev-ref', 'HEAD')

        and: 'the operator round\'s decision file, planted in the box\'s parent environment'
        Path planted = tempDir.resolve('operator-round').resolve('decision.json')
        Files.createDirectories(planted.parent)
        Files.writeString(planted, UNTOUCHED)

        when: 'a child JVM runs one decision-needed round through the fixture box'
        def builder = new ProcessBuilder([
            ProcessHandle.current().info().command().orElseThrow(),
            '-cp',
            System.getProperty('java.class.path'),
            FixtureBoxRoundProcess.getName(),
            clone.toString(),
            tempDir.resolve('box').toString(),
            branch
        ] + FakeAgentBinary.commandPrefix()).redirectErrorStream(true)
        TestChildEnvironment.cleared(builder).put('GNOMISH_DECISION_FILE', planted.toString())
        def process = builder.start()
        String output = process.inputStream.text
        boolean exited = process.waitFor(2, TimeUnit.MINUTES)

        then: 'the round really ran, in a JVM that really held the planted variable'
        exited
        process.exitValue() == 0
        output.readLines().first() == FixtureBoxRoundProcess.PLANTED_PREFIX + planted

        and: 'the fake agent never saw it: the planted file is exactly as it was'
        Files.readString(planted) == UNTOUCHED
    }
}
