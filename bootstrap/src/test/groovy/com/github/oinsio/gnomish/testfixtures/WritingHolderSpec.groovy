package com.github.oinsio.gnomish.testfixtures

import com.github.oinsio.gnomish.sandbox.environment.FakePipe
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import spock.lang.Specification

/**
 * Design D4, D6 of make-pipe-reads-non-parking: {@link WritingHolder}, the descendant that keeps
 * writing to a held {@link FakePipe} after the exit, is what lets a spec reach the post-exit bound
 * of a reader that never parks. Lives in {@code :bootstrap} beside {@code FakePipeSpec}, since
 * {@code :test-fixtures} has no test source set.
 */
class WritingHolderSpec extends Specification {

    def "D4: a writing holder appends one byte before every query, and nothing after the release"() {
        given:
        def pipe = new FakePipe()
        def source = pipe.source()
        WritingHolder.on(pipe)

        expect: 'never an idle query'
        source.available() == 1
        source.available() == 2
        source.read(new byte[2], 0, 2) == 2
        source.available() == 1

        when: 'the write end closes'
        pipe.release()

        then: 'the holder writes nothing more, and the reader reaches end-of-file'
        source.available() == 1
        source.available() == 1
        source.read() == ('.' as char) as int
        source.read() == -1
    }
}
