package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * NFR-R2 of migrate-verify-to-executor-contract (design D19, the single-owner row of {@code
 * GithubRateLimit}): whether a GitHub response is a rate limit, and for how long, is judged in one
 * file. Before task 4.0a a {@code 429} was compared by hand in the retry predicate, in the Actions
 * classification and in the {@code scm-status} reader, and only a {@code 403} carried a judgement,
 * with no pause; a fourth hand-written comparison, or a header read beside the owner, would decide
 * the rate limit a second way, and every unit spec would stay green.
 *
 * <p>The scan covers the GitHub core ({@code githubapi/src/main}) and the core-side GitHub built-ins
 * ({@code adapters/scm/src/main}), comments stripped — javadoc still names the {@code 429} the owner
 * judges. The literal {@code 429} and the header names {@code retry-after}, {@code
 * x-ratelimit-reset} and {@code x-ratelimit-remaining} (any case: HTTP header names are) appear in
 * {@code GithubRateLimit.java} only, and the scan is asserted to have reached that file, so a moved
 * owner fails the gate rather than leaving it vacuous. The seeded features show the detector fires.
 */
class GithubRateLimitOwnerSpec extends Specification {

    private static final String OWNER = 'githubapi/src/main/java/com/github/oinsio/gnomish/githubapi/GithubRateLimit.java'

    private static final List<String> SCANNED = [
        'githubapi/src/main/',
        'adapters/scm/src/main/'
    ]

    /** The literal status and the three header names, as code spells them. */
    private static final Pattern JUDGEMENT = Pattern.compile(/(?i)\b429\b|retry-after|x-ratelimit-reset|x-ratelimit-remaining/)

    // Single-owner row of GithubRateLimit (design D19): the parameter type keeps the value whole
    //     (Fresh carries a RateLimit, no boolean); this scan keeps the judgement in one file.
    def "NFR-R2: no file of the GitHub core or the GitHub built-ins judges a rate limit but GithubRateLimit"() {
        given:
        def sources = RepoSourceTree.productionSources { path ->
            SCANNED.any { path.startsWith(it) }
        }

        expect: 'the scan reached the owner, and the two trees beside it'
        sources.collect { RepoSourceTree.relative(it) }.contains(OWNER)
        sources.size() > 40

        and: 'the owner really holds the judgement, so the scan looks for what exists'
        judges(RepoSourceTree.code(sources.find {
            RepoSourceTree.relative(it) == OWNER
        }))

        and: 'nothing else spells it'
        sources.findAll {
            RepoSourceTree.relative(it) != OWNER && judges(RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) } == []
    }

    def "the detector finds #shape and leaves #spared alone"() {
        expect:
        judges(RepoSourceTree.stripComments(found))
        !judges(RepoSourceTree.stripComments(spared))

        where:
        shape | found | spared
        'a hand comparison' | 'if (status == 429) {' | '/** a {@code 429} is a rate limit */ int s;'
        'a header read' | 'h.firstValue("Retry-After")' | '// reads retry-after through the owner'
        'a reset read' | 'h.firstValue("x-ratelimit-reset")' | 'long n = 14290;'
        'a remaining read' | 'h.firstValue("X-RateLimit-Remaining")' | 'int ratelimit = 0;'
    }

    private static boolean judges(String code) {
        JUDGEMENT.matcher(code).find()
    }
}
