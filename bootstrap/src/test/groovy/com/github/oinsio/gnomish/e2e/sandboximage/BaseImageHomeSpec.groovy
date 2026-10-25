package com.github.oinsio.gnomish.e2e.sandboximage

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.environment.SandboxBaseImage
import com.github.oinsio.gnomish.sandbox.environment.TestDockerCommand
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR3, FR12, NFR-S2 of add-sandbox-base-image (design D14): the gnome's home is root-owned and only
 * the directories declared with {@code gnomish-home-dir} are the gnome's, and the agent policy runs
 * managed hooks only — the layout the base builds, the helper that declares a directory, and the
 * two rules of {@code gnomish-verify-image} that judge the home and the policy, in both directions.
 * The verification judges the {@code gnome} user's home from the user database, never {@code $HOME},
 * so every violation below is made and reported in a throwaway container of the base run as root,
 * whose {@code $HOME} is {@code /root}. The helpers themselves are specified in {@link
 * BaseImageHelpersSpec}.
 */
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class BaseImageHomeSpec extends Specification {

    private static final String VERIFY = 'gnomish-verify-image: '

    @Shared
    String base

    def setupSpec() {
        base = SandboxBaseImage.ensureBuilt()
    }

    // FR12, NFR-S2: the base's home as built — root's, its startup files root-owned stubs, the two
    //     declared directories the gnome's — passes the verification whichever user runs it.
    def "FR12: the base's home is root-owned outside .claude and .cache, and verifies as #user"() {
        expect:
        TestDockerCommand.shell(base, user, 'gnomish-verify-image').exitCode == 0
        TestDockerCommand.shell(base, user, 'cd /home/gnome && stat -c "%U %G %a %n" . .[!.]* && cat .bashrc')
                .success().readLines() == [
                    'root root 755 .',
                    'root root 644 .bash_logout',
                    'root root 644 .bashrc',
                    'gnome gnome 755 .cache',
                    'gnome gnome 755 .claude',
                    'root root 644 .profile',
                    '# Root-owned by the sandbox base image: the gnome cannot change its shell startup.'
                ]

        where:
        user << ['root', 'gnome']
    }

    // FR3, FR12: an operator layer declares a toolchain directory as root; it is the gnome's, listed,
    //     and the verification passes.
    // Scenario: sandbox/sandbox-base-image / An operator declares a toolchain directory
    def "FR3, FR12: gnomish-home-dir .m2 makes a gnome-owned directory the verification accepts"() {
        expect:
        TestDockerCommand.shell(base, 'root', 'gnomish-home-dir .m2 && gnomish-verify-image >/dev/null '
                + '&& stat -c "%U %F" /home/gnome/.m2 && cat /etc/gnomish/home-dirs').success().readLines() == [
                    'gnome directory',
                    '.claude',
                    '.cache',
                    '.m2'
                ]
    }

    // FR3, FR12: a name that is not one entry directly under the home (the home itself, `.`, included:
    //     a chown of it would hand the gnome the whole home), a name of an existing file, and a run as
    //     gnome are refused, and a refused call creates nothing — not even its valid names.
    def "FR3, FR12: gnomish-home-dir refuses '#names' run as #user and changes nothing"() {
        when:
        def result = TestDockerCommand.shell(base, user,
                "gnomish-home-dir ${names}; echo exit=\$?; ls -A /home/gnome; cat /etc/gnomish/home-dirs")

        then:
        def lines = result.output.readLines()
        lines.any { it.startsWith('gnomish-home-dir: ') && it.contains(reason) }
        lines.findAll { !it.startsWith('gnomish-home-dir: ') } == [
            'exit=1',
            '.bash_logout',
            '.bashrc',
            '.cache',
            '.claude',
            '.profile',
            '.claude',
            '.cache'
        ]

        where:
        names | user | reason
        '.ok .m2/../.x' | 'root' | 'not one entry directly under /home/gnome'
        '.ok ..' | 'root' | 'not one entry directly under /home/gnome'
        '.ok .' | 'root' | 'not one entry directly under /home/gnome'
        '.ok .bashrc' | 'root' | '/home/gnome/.bashrc exists and is not a directory'
        '.ok' | 'gnome' | 'must run as root'
    }

    // FR3, FR12, NFR-O1: the home and the policy rules each name the path, what was found and the
    //     expected state; a gnome-owned home is reported though the check runs as root.
    // Scenario: sandbox/sandbox-base-image / An undeclared writable home entry fails the build
    // Scenario: sandbox/sandbox-base-image / An agent policy without managed-hooks-only fails the build
    def "FR3, FR12: gnomish-verify-image reports #breakage"() {
        when:
        def result = TestDockerCommand.shell(base, 'root', "${breakage}; gnomish-verify-image")

        then:
        result.exitCode == 1
        def violations = result.output.readLines().findAll {
            it.startsWith(VERIFY) && it.contains('; fix: ')
        }
        violations.size() == 1
        violations[0].startsWith(VERIFY + expected)
        result.output.contains("${VERIFY}1 violation(s) of the image contract")

        where:
        breakage || expected
        'chown gnome /home/gnome' || "/home/gnome: owner and mode '1000 755'; expected owned by root"
        'chown gnome /home/gnome/.bashrc' || "/home/gnome/.bashrc: owner and mode '1000 644'; expected owned by root"
        'mkdir /home/gnome/.config && chown gnome /home/gnome/.config' || "/home/gnome/.config: owner and mode '1000 755'; expected owned by root"
        'rmdir /home/gnome/.cache' || '/home/gnome/.cache: missing, though listed in /etc/gnomish/home-dirs'
        "printf '{}\\n' > /etc/claude-code/managed-settings.json" || '/etc/claude-code/managed-settings.json: allowManagedHooksOnly is not set to true; expected "allowManagedHooksOnly": true'
    }
}
