package com.github.oinsio.gnomish.subprocess

import spock.lang.Specification

/**
 * FR18, FR22, design D16 of fix-terminal-receipt-convergence: {@link ProcessLaunch} is the one way
 * a supervised process is started, and {@link ProcessLaunch#grouped()} is its one production form.
 * What is pinned here is that the form runs the command the builder describes — the launcher in
 * front of it (task 8.19) changes the process group, not the program — and that an unlaunchable
 * binary is still the JDK's own exception, the caller's classification (NG4); the group and the
 * resolution before the launcher are {@code HostProcessLaunchSpec}'s.
 */
class ProcessLaunchSpec extends Specification implements FakeBinaries {

    def "FR18: the production form starts the builder it was handed"() {
        given: 'a binary that exits at once with its own code'
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('quick').toString())

        when:
        Process process = ProcessLaunch.grouped().start(builder, StdinSource.NONE)

        then: 'the process that ran is that command'
        process.waitFor() == 3
    }

    def "FR18: a binary that cannot be launched surfaces as the JDK's own exception"() {
        when:
        ProcessLaunch.grouped().start(new ProcessBuilder('/nonexistent/gnomish-no-such-binary'), StdinSource.NONE)

        then:
        thrown(IOException)
    }
}
