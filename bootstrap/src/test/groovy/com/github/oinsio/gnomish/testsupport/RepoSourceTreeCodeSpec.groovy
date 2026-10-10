package com.github.oinsio.gnomish.testsupport

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The comment stripper every whole-tree gate scans through ({@link RepoSourceTree#code}): it must
 * keep exactly what the compiler sees. A stripper that drops a line of code is a blind spot in
 * every gate at once, and one that keeps a comment is a false hit; over a clean tree neither shows,
 * so only seeded sources tell a working stripper from a broken one (FR16, FR18 of
 * supervise-daemon-loops-and-embed-dashboard).
 */
class RepoSourceTreeCodeSpec extends Specification {

    @TempDir
    Path dir

    def "keeps the code and drops the comment: #shape"() {
        given:
        def file = dir.resolve('Seed.java').toFile()
        file.text = source

        expect:
        RepoSourceTree.code(file).contains('MARK') == codeKept

        where:
        shape | source || codeKept
        'code after a leading block comment' | '/* why */ MARK.run();' || true
        'a continuation line opening with *' | 'long x = a\n        * MARK;' || true
        'code after a block closing mid-line' | '/* one\n   two */ MARK.run();' || true
        'a trailing block comment' | 'run(); /* MARK */' || false
        'a block line not opening with *' | '/*\n  MARK\n */' || false
        'a javadoc line' | '/**\n * MARK\n */' || false
        'a line comment' | 'run(); // MARK' || false
        'a comment opener inside a string' | 'var s = "/*"; MARK.run(); var t = "*/";' || true
        'a line-comment opener inside a string' | 'var s = "http://host"; MARK.run();' || true
        'a text block holding an opener' | 'var s = """\n  /* not a comment\n  """; MARK();' || true
        'a character literal quote' | "char c = '\"'; MARK.run(); // \"" || true
    }
}
