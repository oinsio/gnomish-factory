package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR9, UX1 of add-sandbox-base-image (task 6.1): the operator guide's sandbox chapter shows the
 * reference recipe, and the Dockerfile it shows is the recipe's — instruction for instruction, the
 * comment and blank lines of either aside. The guide is not checked by reading: a recipe edit the
 * guide does not follow, or a guide edit the recipe does not carry, fails here.
 *
 * <p>The block is found by its marker, an HTML comment naming the recipe file, on the line directly
 * above the opening {@code ```dockerfile} fence, so the guide may hold other Dockerfile snippets
 * (one {@code ENV}, one {@code RUN}) without the spec guessing which block is the recipe. The marker
 * must occur exactly once.
 */
class RecipeGuideExampleSpec extends Specification {

    static final String MARKER = '<!-- recipe: docs/examples/sandbox-image/Dockerfile -->'

    private static final Path ROOT = RepoSourceTree.repoRoot()

    // FR9, UX1: the guide's recipe block equals the recipe, comment lines aside
    def "FR9: the guide's marked Dockerfile block carries exactly the recipe's instructions"() {
        given:
        def guide = Files.readAllLines(ROOT.resolve('docs/guides/operator-guide-sandbox.md'))
        def recipe = Files.readAllLines(ROOT.resolve('docs/examples/sandbox-image/Dockerfile'))

        expect:
        guide.count { it.strip() == MARKER } == 1
        instructions(markedBlock(guide)) == instructions(recipe)
    }

    // FR9: the comparison is red whichever side drifts, and only comments and blank lines may differ
    def "FR9: the comparison #outcome when #change"() {
        expect:
        (instructions(BASELINE.collectMany(edit)) == instructions(BASELINE)) == equal

        where:
        change | edit || equal
        'a comment line is added' | {
            it == 'USER root' ? ['# guide note', it] : [it]
        } || true
        'a blank line is dropped' | {
            it == '' ? [] : [it]
        } || true
        'an instruction changes' | {
            it == 'USER root' ? ['USER gnome'] : [it]
        } || false
        'an instruction is dropped' | {
            it == 'USER root' ? [] : [it]
        } || false
        'an instruction is added' | {
            it == 'USER gnome' ? ['RUN extra', it] : [it]
        } || false
        'a continuation line changes' | {
            it == ' && b' ? [' && c'] : [it]
        } || false
        outcome = equal ? 'stays green' : 'turns red'
    }

    private static final List<String> BASELINE = [
        '# a comment',
        'FROM base:${TAG}',
        'USER root',
        '',
        'RUN a \\',
        ' && b',
        'USER gnome'
    ].asImmutable()

    /** The fenced block on the lines after the marker; the fence must open on the very next line. */
    private static List<String> markedBlock(List<String> guide) {
        int marker = guide.findIndexOf { it.strip() == MARKER }
        assert guide[marker + 1].strip() == '```dockerfile': "the line after ${MARKER} must open a dockerfile fence"
        int close = guide.findIndexOf(marker + 2) { it.strip() == '```' }
        assert close> 0: "the dockerfile block after ${MARKER} is never closed"
        guide.subList(marker + 2, close)
    }

    /** A Dockerfile's instruction lines: blank lines and comment lines dropped, the rest verbatim. */
    private static List<String> instructions(List<String> lines) {
        lines.findAll {
            it.strip() && !it.strip().startsWith('#')
        }
    }
}
