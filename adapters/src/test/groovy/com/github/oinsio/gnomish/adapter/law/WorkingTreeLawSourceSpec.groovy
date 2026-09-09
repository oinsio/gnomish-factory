package com.github.oinsio.gnomish.adapter.law

import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR11, D12 of add-base-ref-resolution: the working-tree realization's own guard detail — which
 * segment of a reference the symlink refusal stops at, and what the refusal reads like. The
 * cross-medium verdict itself (a symlink entry is refused in a working tree exactly as in a git
 * tree) belongs to {@link LawSourceContractSpec}, together with read, regular-file test and
 * listing.
 */
class WorkingTreeLawSourceSpec extends Specification {

    @TempDir
    Path tempDir

    private Path root

    def setup() {
        root = tempDir.resolve('.gnomish')
        Files.createDirectories(root)
        Files.writeString(root.resolve('instructions.md'), 'Do the thing.')
        Files.writeString(tempDir.resolve('outside.md'), 'Not law.')
    }

    def "FR11, D12: a symlink resolving inside the root is refused, never read as its target"() {
        given:
        Files.createSymbolicLink(root.resolve('alias.md'), Path.of('instructions.md'))
        def source = new WorkingTreeLawSource(root)

        expect: 'the git-objects realization cannot follow it, so neither does this one'
        source.fileStatus('alias.md') == LawSource.FileStatus.REFUSED
        source.read('alias.md') instanceof LawSource.Unreadable
        !((LawSource.Unreadable) source.read('alias.md')).reason().contains('Do the thing.')
    }

    def "FR11, D12: a symlinked directory hides everything beneath it"() {
        given: 'a real stage directory and a symlink standing in for it'
        Files.createDirectories(root.resolve('stages/plan'))
        Files.writeString(root.resolve('stages/plan/instructions.md'), 'Plan it.')
        Files.createSymbolicLink(root.resolve('linked'), Path.of('stages'))
        def source = new WorkingTreeLawSource(root)

        expect: 'the walk stops at the linked segment, so nothing under it is law'
        source.fileStatus('linked/plan/instructions.md') == LawSource.FileStatus.REFUSED
        source.read('linked/plan/instructions.md') instanceof LawSource.Unreadable
        source.list('linked') == []

        and: 'the same files reached by their real path are ordinary law'
        source.fileStatus('stages/plan/instructions.md') == LawSource.FileStatus.REGULAR_FILE
    }

    def "FR11, D12: a symlink whose target leaves the root is refused"() {
        given:
        Files.createSymbolicLink(root.resolve('escape.md'), Path.of('../outside.md'))
        def source = new WorkingTreeLawSource(root)

        expect:
        source.fileStatus('escape.md') == LawSource.FileStatus.REFUSED
        source.read('escape.md') instanceof LawSource.Unreadable
    }

    def "FR11: a symlink entry is listed as OTHER, never as what it points at"() {
        given: 'a dangling link — it names no file and no directory'
        Files.createSymbolicLink(root.resolve('dangling.md'), Path.of('gone.md'))
        def source = new WorkingTreeLawSource(root)

        expect:
        source.list('').find {
            it.name() == 'dangling.md'
        }.kind() == LawEntry.Kind.OTHER
    }

    def "FR11: an entry that is neither file, directory nor link is listed as OTHER"() {
        given: 'a unix-domain socket under a law root short enough for the socket path limit'
        Path shortRoot = Files.createTempDirectory(Path.of('/tmp'), 'gl')
        def channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(shortRoot.resolve('socket')))
        def source = new WorkingTreeLawSource(shortRoot)

        expect: 'the listing tells it apart from a law file, so it can never be read as one'
        source.list('').find {
            it.name() == 'socket'
        }.kind() == LawEntry.Kind.OTHER

        and: 'read by name it is refused as not a regular file — a different reason from an absent path'
        source.fileStatus('socket') == LawSource.FileStatus.ABSENT
        ((LawSource.Unreadable) source.read('socket')).reason().contains('not a regular file')

        and: 'listing it is empty — an entry that is not a directory holds no law beneath it'
        source.list('socket') == []

        cleanup:
        channel.close()
        Files.deleteIfExists(shortRoot.resolve('socket'))
        Files.deleteIfExists(shortRoot)
    }

    def "FR11: an unreadable law file carries the reason it could not be read"() {
        given:
        def source = new WorkingTreeLawSource(root)

        when:
        def read = source.read('absent.md')

        then:
        read instanceof LawSource.Unreadable
        ((LawSource.Unreadable) read).reason().contains('absent.md')
        ((LawSource.Unreadable) read).reason().contains('no law file')
    }

    // FR13, D14: an I/O fault on a regular file is data, not an exception — the reason names the
    //     fault so it can be diagnosed at the point of use.
    def "FR11: a regular file that cannot be decoded is unreadable with the I/O fault as its reason"() {
        given: 'bytes no UTF-8 decoder accepts'
        Files.write(root.resolve('binary.md'), [0xC3, 0x28] as byte[])
        def source = new WorkingTreeLawSource(root)

        when:
        def read = source.read('binary.md')

        then:
        read instanceof LawSource.Unreadable
        ((LawSource.Unreadable) read).reason().contains('MalformedInputException')
    }
}
