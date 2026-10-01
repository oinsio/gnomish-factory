package com.github.oinsio.gnomish.adapter.secrets

import com.github.oinsio.gnomish.app.UsageException
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectLayout
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.function.Function
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The secrets folders of the env/file adapter (FR8, NFR-S2, design D7): the project's folder, then
 * the host's, ahead of {@code N_FILE} and {@code N}; the file name is the secret's variable name;
 * a folder file group or others may read or write is refused, never read.
 *
 * <p>Implements FR8, NFR-S2 of add-project-registry.
 */
class EnvFileSecretsFolderSpec extends Specification {

    private static final String TOKEN = 'GNOMISH_GITHUB_TOKEN'

    @TempDir
    Path tempDir

    FactoryHome home
    ProjectLayout widgets

    def setup() {
        home = FactoryHome.at(tempDir.resolve('home'))
        widgets = home.project(new ProjectName('widgets'))
    }

    // FR8: "Project secret wins over host and environment"
    def "FR8: the project folder's file wins over the host folder, N_FILE and N"() {
        given:
        secret(widgets.secret(TOKEN), 'from-project\n')
        secret(home.hostSecret(TOKEN), 'from-host')
        def viaFile = secret(tempDir.resolve('elsewhere').resolve(TOKEN), 'from-file')

        expect:
        providerFor([(TOKEN): 'from-env', (TOKEN + '_FILE'): viaFile.toString()], widgets).find(TOKEN) ==
        Optional.of('from-project')
    }

    // FR8: "Shared agent token from the host folder"
    def "FR8: a secret only the host folder holds resolves for every project and for none"() {
        given:
        secret(home.hostSecret('CLAUDE_CODE_OAUTH_TOKEN'), 'shared')

        expect:
        [
            widgets,
            home.project(new ProjectName('gadgets')),
            null
        ].every { ProjectLayout project ->
            providerFor([CLAUDE_CODE_OAUTH_TOKEN: 'from-env'], project).find('CLAUDE_CODE_OAUTH_TOKEN') ==
            Optional.of('shared')
        }
    }

    // FR8: the host folder comes ahead of the environment
    def "FR8: the host folder's file wins over N_FILE and N"() {
        given:
        secret(home.hostSecret(TOKEN), 'from-host')
        def viaFile = secret(tempDir.resolve('elsewhere').resolve(TOKEN), 'from-file')

        expect:
        providerFor([(TOKEN): 'from-env', (TOKEN + '_FILE'): viaFile.toString()], widgets).find(TOKEN) ==
        Optional.of('from-host')
    }

    // FR8: a command with no project never reads a project folder
    def "FR8: with no project resolved, a project folder's file is not consulted"() {
        given:
        secret(widgets.secret(TOKEN), 'from-project')

        expect:
        providerFor([(TOKEN): 'from-env'], null).find(TOKEN) == Optional.of('from-env')
    }

    // NFR-S2: "Loose permissions are refused"
    def "NFR-S2: a #where folder file with mode #mode is refused naming the file and the chmod"() {
        given:
        def file = secret(where == 'project' ? widgets.secret(TOKEN) : home.hostSecret(TOKEN), 'leaked', mode)

        when:
        providerFor([(TOKEN): 'from-env'], widgets).find(TOKEN)

        then:
        def refused = thrown(UsageException)
        refused.message.contains(file.toString())
        refused.message.contains("chmod 600 ${file}")
        !refused.message.contains('leaked')

        where:
        mode | where
        'rw-r-----' | 'project'
        'rw--w----' | 'host'
        'rw----r--' | 'project'
        'rw-----w-' | 'host'
    }

    // NFR-S2: only the folders the factory home owns are checked; an N_FILE target keeps its contract
    def "NFR-S2: a world-readable file named by N_FILE is still read"() {
        given:
        def mounted = secret(tempDir.resolve('run-secrets').resolve(TOKEN), 'mounted', 'rw-r--r--')

        expect:
        providerFor([(TOKEN + '_FILE'): mounted.toString()], widgets).find(TOKEN) == Optional.of('mounted')
    }

    // NFR-S1 of add-sandbox-core: a folder file is the answer — blank is absent, never a fall-through
    def "FR8: a blank folder file resolves to empty, not to the environment"() {
        given:
        secret(home.hostSecret(TOKEN), '  \n')

        expect:
        providerFor([(TOKEN): 'from-env'], widgets).find(TOKEN) == Optional.empty()
    }

    // FR5, NFR-S1 of harden-logging-observability: an unreadable folder file warns naming the file
    def "FR8: an unreadable folder file fails closed with one warning naming the file"() {
        given: 'a private directory where the file would be, so the read fails'
        def unreadable = Files.createDirectories(home.hostSecret(TOKEN))
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString('rwx------'))

        when:
        def logs = LogCaptureSupport.attach(EnvFileSecretsProvider)
        def resolved = providerFor([(TOKEN): 'from-env'], widgets).find(TOKEN)
        def warnings = List.copyOf(logs.list).findAll {
            it.level.levelStr == 'WARN'
        }
        logs.detach()

        then:
        resolved == Optional.empty()
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.SECRET_FILE_UNREADABLE.head())
        warnings[0].formattedMessage.contains(unreadable.toString())
        !warnings[0].formattedMessage.contains('from-env')
    }

    // FR8: the file name is the secret's variable name — one segment, never a path out of the folder
    def "FR8: a secret name that is #shape is refused, and no file outside the folders is read"() {
        given:
        def outside = secret(tempDir.resolve('outside').resolve(TOKEN), 'outside-value')
        String name = shape == 'an absolute path' ? outside.toString() : "../../../outside/$TOKEN".toString()

        when:
        providerFor([:], where == 'project' ? widgets : null).find(name)

        then:
        def refused = thrown(UsageException)
        refused.message.contains(name)
        !refused.message.contains('outside-value')

        where:
        shape | where
        'a relative path' | 'project'
        'an absolute path' | 'host'
    }

    private EnvFileSecretsProvider providerFor(Map<String, String> vars, ProjectLayout project) {
        new EnvFileSecretsProvider(home, project, { String name ->
            vars.get(name)
        } as Function)
    }

    /** Writes a secret file with the given mode — private by default, whatever the umask. */
    private static Path secret(Path file, String value, String mode = 'rw-------') {
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode))
        file
    }
}
