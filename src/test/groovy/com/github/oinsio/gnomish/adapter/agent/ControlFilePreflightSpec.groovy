package com.github.oinsio.gnomish.adapter.agent

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, D8: {@link ControlFilePreflight} is the CLI-executor-side half of D8's
 * per-adapter control-file policy — where the interactive adapter degrades an
 * unreadable control file to a placeholder string ({@code StageBriefingSpec}),
 * the CLI executor must stop before the agent process ever spawns, since a
 * silently control-less prompt could pass verify with nobody noticing.
 */
class ControlFilePreflightSpec extends Specification {

    @TempDir
    Path workspaceRoot

    def "a readable control file returns its content unchanged"() {
        given:
        Files.writeString(workspaceRoot.resolve('instructions.md'), 'Follow the coding standard.')

        when:
        def content = ControlFilePreflight.read(workspaceRoot, 'instructions.md')

        then:
        content == 'Follow the coding standard.'
    }

    def "a missing control file throws UnreadableControlFileException naming the ref"() {
        when:
        ControlFilePreflight.read(workspaceRoot, 'does-not-exist.md')

        then:
        def e = thrown(ControlFilePreflight.UnreadableControlFileException)
        e.message.contains('does-not-exist.md')

        and: 'the reason is the IOException\'s own message (the resolved path), not its class name'
        e.message.contains(workspaceRoot.resolve('does-not-exist.md').toString())
        !e.message.contains('NoSuchFileException')
    }

    def "a control file reference escaping the workspace throws UnreadableControlFileException naming the ref"() {
        when:
        ControlFilePreflight.read(workspaceRoot, '../secret.md')

        then:
        def e = thrown(ControlFilePreflight.UnreadableControlFileException)
        e.message.contains('../secret.md')
        e.message.contains('escapes')
    }

    @Requires({ !os.windows })
    def "an unreadable control file (permission denied) throws UnreadableControlFileException"() {
        given:
        def path = workspaceRoot.resolve('locked.md')
        Files.writeString(path, 'secret content')
        path.toFile().setReadable(false)

        when:
        ControlFilePreflight.read(workspaceRoot, 'locked.md')

        then:
        thrown(ControlFilePreflight.UnreadableControlFileException)

        cleanup:
        path.toFile().setReadable(true)
    }
}
