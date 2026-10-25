package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Design D22 of add-sandbox-base-image, the {@code OperatorPreconditionRefusals} row:
 * the classifier is the one place {@code :application} decides whether a failure is an operator
 * precondition refusal, and which. Before it, the claimed-run dispatch, {@code run}'s reporting
 * (two lists) and {@code run}'s exit code each held their own list of the four types, so a fifth
 * precondition had to be added in four places, and one forgotten would compile and pass its own
 * spec. This scan keeps classification in the owner: no other file of the layer names a refusal
 * type in a {@code case}, an {@code instanceof} or a {@code .class} literal. Declarations, throw
 * sites and javadoc links are no classification and stay free.
 *
 * <p>FR14, NFR-R2 of add-sandbox-base-image.
 */
class OperatorPreconditionOwnerSpec extends Specification {

    private static final String LAYER = 'application/src/main/'

    private static final String OWNER =
    'application/src/main/java/com/github/oinsio/gnomish/app/OperatorPreconditionRefusals.java'

    private static final List<String> REFUSALS = [
        'CloneRefFormatRefusedException',
        'ContainerRuntimeUnavailableException',
        'BoxGitVersionRefusedException',
        'ImageContractRefusedException',
    ]

    // Scenario: tracker/tracker-take / Operator precondition refused after the claim exits 8
    def "#refusal is classified in :application only by OperatorPreconditionRefusals"() {
        given: 'every production source of the layer, comments removed'
        def sources = RepoSourceTree.productionSources().findAll {
            RepoSourceTree.relative(it).startsWith(LAYER)
        }

        expect: 'the scan reached the layer and the owner, so a rename cannot leave the gate naming nothing'
        sources.size() > 100
        sources.collect { RepoSourceTree.relative(it) }.contains(OWNER)

        when:
        def classifiers = sources.findAll {
            classifies(RepoSourceTree.code(it), refusal)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'the owner, and only the owner: an extra file is a second list of the preconditions'
        classifiers == [OWNER]

        where:
        refusal << REFUSALS
    }

    // The detector is the gate: a seeded classification is found, a throw or a declaration is not
    def "the detector: #shape"() {
        expect:
        classifies(RepoSourceTree.codeOnly(line), 'ImageContractRefusedException') == detected

        where:
        shape | line || detected
        'a switch arm' | 'case ImageContractRefusedException refused -> 8;' || true
        'an instanceof' | 'if (ex instanceof ImageContractRefusedException) {' || true
        'a class literal' | 'ImageContractRefusedException.class, // a list entry' || true
        'a throw site' | 'throw new ImageContractRefusedException(image, found, label, 2, fix);' || false
        'a declaration' | 'public final class ImageContractRefusedException extends RuntimeException {' || false
        'a javadoc link' | ' * the refusal ({@link ImageContractRefusedException}) ends the run' || false
    }

    private static boolean classifies(String code, String refusal) {
        Pattern.compile(/\bcase\s+${refusal}\b|\binstanceof\s+${refusal}\b|\b${refusal}\.class\b/).matcher(code).find()
    }
}
