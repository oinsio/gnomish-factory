package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR4 and design D1, D4 of add-doctor-command: the launch owner's two non-refusing verdicts —
 * the host's helpers and a command's executable — are the decision the launch refuses on, read
 * without starting a process. Each feature puts the verdict beside a real launch over the same
 * inputs and asserts the identity: the launch refuses exactly when the verdict fails, with the
 * verdict's reason as its message, and starts exactly what the verdict resolved.
 *
 * <p>The launch is handed a recording launcher ({@code launcher-record}) wherever it would succeed:
 * its log exists exactly when a process was started. The helpers and the sweep's {@code kill} are
 * handed in as parameters, so both platforms' branches run on whichever host runs the spec.
 */
class LaunchAssessmentSpec extends Specification implements FakeBinaries {

    @TempDir
    Path inputs

    @TempDir
    Path work

    def "FR4: the launch refuses exactly when the helper verdict is missing: #shape"() {
        given: 'helpers placed by the row: a recording launcher where one is present, nothing where it is absent'
        Path bin = Files.createDirectory(work.resolve('bin'))
        Path perl = perlThere ? StandIn.link(work.resolve('perl'), 'launcher-record') : work.resolve('no-perl')
        if (setsidThere) {
            StandIn.link(bin.resolve('setsid'), 'launcher-record')
        }
        Path kill = killThere ? StandIn.link(work.resolve('kill'), 'quick') : work.resolve('no-kill')
        HostLauncher helpers = new HostLauncher(osName, perl, bin.toString(), kill)
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('quick').toString())
        TestChildEnvironment.cleared(builder)

        when:
        HelperVerdict verdict = helpers.assess()
        String refusal = launchRefusal(new GroupedLaunch(inputs, null, helpers), builder)

        then: 'the verdict names what the launch refuses on, word for word, or the launch went ahead'
        verdict.class == expected
        if (verdict instanceof HelperVerdict.Missing) {
            assert refusal == verdict.reason()
            assert verdict.tool() == tool.replace('@work', work.toString())
            assert verdict.install().contains(install)
            assert !launched(perl) && !launched(bin.resolve('setsid'))
        } else {
            assert refusal == null
            assert verdict.kill() == kill.toString()
            assert launched(Path.of(verdict.launcher()))
        }

        where:
        shape | osName | perlThere | setsidThere | killThere || expected | tool | install
        'macOS with perl and kill' | 'Mac OS X' | true | false | true || HelperVerdict.Present | '' | ''
        'macOS without perl' | 'Mac OS X' | false | true | true || HelperVerdict.Missing | '@work/no-perl' | 'macOS ships it'
        'macOS without kill' | 'Mac OS X' | true | true | false || HelperVerdict.Missing | '@work/no-kill' | 'procps'
        'Linux with setsid and kill' | 'Linux' | true | true | true || HelperVerdict.Present | '' | ''
        'Linux without setsid on the JVM PATH' | 'Linux' | true | false | true || HelperVerdict.Missing | 'setsid' | 'util-linux'
        'Linux without kill' | 'Linux' | true | true | false || HelperVerdict.Missing | '@work/no-kill' | 'procps'
        'a platform with no launcher' | 'Windows 11' | true | true | true || HelperVerdict.Missing | "a process-group launcher for os.name 'Windows 11'" | 'Linux and macOS only'
    }

    def "FR4: the Linux refusal keeps the lookup's own refusal as its cause"() {
        given:
        HostLauncher helpers = new HostLauncher('Linux', work.resolve('perl'), work.toString(), StandIn.link(work.resolve('kill'), 'quick'))

        when:
        helpers.prefix()

        then:
        IOException refused = thrown()
        refused.cause.message.contains('Cannot run program "setsid"')
    }

    def "FR1: the public helper verdict is the platform's own, and this host has both helpers"() {
        expect: 'the spec hosts — macOS and the Linux runners — carry the platform launcher and /bin/kill'
        ProcessLaunch.assessHelper() == new HostLauncher(System.getProperty('os.name', ''), System.getenv('PATH'), HostLauncher.KILL).assess()
        ProcessLaunch.assessHelper() instanceof HelperVerdict.Present
        (ProcessLaunch.assessHelper() as HelperVerdict.Present).kill() == '/bin/kill'
    }

    def "FR4: the launch refuses a command exactly when the executable verdict is unresolved: #shape"() {
        given: 'the row\'s entry ahead of the baseline PATH the recording launcher needs, and a fake PATH directory holding a runnable binary, a file without the x bit, and a script whose interpreter is missing'
        Path bin = Files.createDirectory(work.resolve('bin'))
        StandIn.link(bin.resolve('gnomish-probe'), 'quick')
        Files.writeString(bin.resolve('not-executable'), 'data')
        Files.createSymbolicLink(bin.resolve('no-interpreter'), fakeBinary('missing-interpreter'))
        StandIn.link(work.resolve('setsid'), 'launcher-record')
        GroupedLaunch owner = new GroupedLaunch(inputs, 'Linux', work.toString())
        ProcessBuilder builder = onPath(path.replace('@bin', bin.toString()), name).directory(work.toFile())
        String childPath = builder.environment().get('PATH')

        when:
        ExecutableVerdict verdict = owner.assessExecutable(name, work.toFile(), childPath)
        String refusal = launchRefusal(owner, builder)

        then:
        verdict.class == expected
        if (verdict instanceof ExecutableVerdict.Unresolved) {
            assert refusal == verdict.reason()
            assert verdict.reason().contains(name)
            assert !Files.exists(StandIn.log(work.resolve('setsid')))
        } else {
            assert refusal == null
            assert verdict.executable() == bin.resolve(name)
            assert Files.readString(StandIn.log(work.resolve('setsid'))).contains("argv=${bin.resolve(name)}")
        }

        where:
        shape | name | path || expected
        'a runnable binary on an absolute entry' | 'gnomish-probe' | '@bin' || ExecutableVerdict.Resolved
        'the same binary on a relative entry' | 'gnomish-probe' | 'bin' || ExecutableVerdict.Unresolved
        'a file without the x bit' | 'not-executable' | '@bin' || ExecutableVerdict.Unresolved
        'a script whose #! interpreter is missing' | 'no-interpreter' | '@bin' || ExecutableVerdict.Unresolved
        'a name on no PATH' | 'gnomish-probe' | '/nonexistent' || ExecutableVerdict.Unresolved
    }

    def "FR1: the public executable verdict resolves against the directory and the PATH it is handed"() {
        given:
        Path bin = Files.createDirectory(work.resolve('bin'))
        Path probe = StandIn.link(bin.resolve('gnomish-probe'), 'quick')

        expect: 'a bare name on the PATH handed in, and a relative path against the working directory'
        ProcessLaunch.assessExecutable('gnomish-probe', null, bin.toString()) == new ExecutableVerdict.Resolved(probe)
        ProcessLaunch.assessExecutable('bin/gnomish-probe', work, '/nonexistent') == new ExecutableVerdict.Resolved(work.resolve('bin/gnomish-probe'))
        ProcessLaunch.assessExecutable('gnomish-probe', null, '/nonexistent') instanceof ExecutableVerdict.Unresolved
    }

    def "FR1: with no PATH handed in, the JVM's stands in for it, as at the launch"() {
        given: 'a name the JVM\'s PATH carries on every spec host'
        ExecutableVerdict verdict = ProcessLaunch.assessExecutable('sh', null, null)

        expect:
        verdict instanceof ExecutableVerdict.Resolved
        (verdict as ExecutableVerdict.Resolved).executable().isAbsolute()
        (verdict as ExecutableVerdict.Resolved).executable().fileName.toString() == 'sh'
    }

    /** The launch's refusal message, or {@code null} when it started the command (and waited for it). */
    private static String launchRefusal(GroupedLaunch owner, ProcessBuilder builder) {
        try {
            owner.start(builder, StdinSource.NONE).waitFor()
            return null
        } catch (IOException refused) {
            return refused.message
        }
    }

    private static boolean launched(Path recorder) {
        Files.exists(StandIn.log(recorder))
    }
}
