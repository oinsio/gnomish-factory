package com.github.oinsio.gnomish.baseref

import spock.lang.Specification

/**
 * {@link BaseRefResolver}: the one priority order of base-ref-resolution.
 *
 * <p>FR4: explicit {@code --base} &gt; the task's designator validated against the allowed bases &gt; the
 * configured default &gt; the repository default branch; a manual run without {@code --base} alone
 * falls through to the clone's local HEAD. A conflicting or not-allowed designator escalates instead
 * of falling through.
 *
 * <p>FR5: an autonomous path never falls back to the local HEAD — with no default branch it refuses.
 *
 * <p>FR10: values in, a decision out; the same inputs always produce the same decision.
 */
class BaseRefResolverSpec extends Specification {

    private static final AllowedBases ALLOWED = AllowedBases.of([
        AllowedBase.of(BasePattern.compile('main')),
        new AllowedBase(BasePattern.compile('release/*'), BranchRole.RELEASE),
    ])

    private static BaseRefRequest request(Map overrides) {
        new BaseRefRequest(
                overrides.containsKey('explicitBase') ? overrides.explicitBase : null,
                overrides.designator ?: BaseDesignator.absent(),
                overrides.containsKey('allowedBases') ? overrides.allowedBases : ALLOWED,
                overrides.containsKey('configuredDefault') ? overrides.configuredDefault : null,
                overrides.mode ?: ResolutionMode.AUTONOMOUS,
                overrides.containsKey('defaultBranch') ? overrides.defaultBranch : null)
    }

    // FR4: the whole priority matrix — each row silences every tier above it and exercises one tier
    def "#scenario resolves '#ref' by #rule"() {
        when:
        def resolution = BaseRefResolver.resolve(request(inputs))

        then:
        resolution instanceof BaseResolution.Resolved
        resolution.decision().ref() == ref
        resolution.decision().rule() == rule
        !resolution.decision().reason().isBlank()

        where:
        scenario | inputs || ref | rule
        'an explicit --base with nothing else' | [explicitBase: 'v1.2.3'] || 'v1.2.3' | BaseRule.EXPLICIT_ARGUMENT
        'an explicit --base over a valid designator' | [explicitBase: 'v1.2.3', designator: BaseDesignator.single('release/1.18'), configuredDefault: 'develop', defaultBranch: 'trunk'] || 'v1.2.3' | BaseRule.EXPLICIT_ARGUMENT
        'an explicit --base that is not allowed' | [explicitBase: 'experiments/foo'] || 'experiments/foo' | BaseRule.EXPLICIT_ARGUMENT
        'an explicit --base over a conflict' | [explicitBase: 'v1.2.3', designator: BaseDesignator.conflict(['a', 'b'])] || 'v1.2.3' | BaseRule.EXPLICIT_ARGUMENT
        'a designator over the configured default' | [designator: BaseDesignator.single('release/1.18'), configuredDefault: 'develop', defaultBranch: 'trunk'] || 'release/1.18' | BaseRule.DESIGNATOR
        'the configured default over the remote' | [configuredDefault: 'develop', defaultBranch: 'trunk'] || 'develop' | BaseRule.CONFIGURED_DEFAULT
        'zero configuration' | [allowedBases: AllowedBases.empty(), defaultBranch: 'trunk'] || 'trunk' | BaseRule.REPOSITORY_DEFAULT_BRANCH
        'a manual run without --base' | [mode: ResolutionMode.MANUAL, allowedBases: AllowedBases.empty()] || 'HEAD' | BaseRule.LOCAL_HEAD
        'a manual run whose remote answered' | [mode: ResolutionMode.MANUAL, defaultBranch: 'trunk'] || 'trunk' | BaseRule.REPOSITORY_DEFAULT_BRANCH
        'a manual run with --base' | [mode: ResolutionMode.MANUAL, explicitBase: 'v1.2.3'] || 'v1.2.3' | BaseRule.EXPLICIT_ARGUMENT
    }

    // FR4, UX2: an underdetermined designator stops resolution — it never falls through to a default
    def "#scenario is underdetermined by #cause"() {
        when:
        def resolution = BaseRefResolver.resolve(request(inputs))

        then:
        resolution instanceof BaseResolution.Underdetermined
        resolution.cause() == cause
        resolution.values() == values
        resolution.reason().contains(reasonFragment)

        where:
        scenario | inputs || cause | values | reasonFragment
        'a designator that is not allowed' | [designator: BaseDesignator.single('experiments/foo'), configuredDefault: 'develop', defaultBranch: 'trunk'] || UnderdeterminedCause.DESIGNATOR_NOT_ALLOWED | ['experiments/foo'] | 'the allowed bases are main, release/*'
        'two base labels' | [designator: BaseDesignator.conflict([
                'release/1.18',
                'release/1.19'
            ]), configuredDefault: 'develop'] || UnderdeterminedCause.DESIGNATOR_CONFLICT | [
            'release/1.18',
            'release/1.19'
        ] | 'resolution never picks one'
        'a designator with no allowed base' | [designator: BaseDesignator.single('release/1.18'), allowedBases: AllowedBases.empty(), defaultBranch: 'trunk'] || UnderdeterminedCause.DESIGNATOR_NOT_ALLOWED | ['release/1.18'] | 'the allowed bases are (empty)'
        'an autonomous run with no default branch' | [allowedBases: AllowedBases.empty()] || UnderdeterminedCause.NO_DEFAULT_BRANCH | [] | "never falls back to the clone's local HEAD"
        'a manual run under a conflict' | [mode: ResolutionMode.MANUAL, designator: BaseDesignator.conflict(['a', 'b'])] || UnderdeterminedCause.DESIGNATOR_CONFLICT | ['a', 'b'] | 'more than one base'
    }

    // FR5: the manual tier is the only fallback to the working copy; autonomous refuses instead
    def "only a manual run falls through to the local HEAD"() {
        given: 'the same inputs but for the mode: nothing names a base, no remote answered'
        def manual = BaseRefResolver.resolve(request(mode: ResolutionMode.MANUAL, allowedBases: AllowedBases.empty()))
        def autonomous = BaseRefResolver.resolve(request(mode: ResolutionMode.AUTONOMOUS, allowedBases: AllowedBases.empty()))

        expect:
        manual.decision().ref() == BaseRefResolver.LOCAL_HEAD_REF
        manual.decision().rule() == BaseRule.LOCAL_HEAD

        and:
        autonomous instanceof BaseResolution.Underdetermined
        autonomous.cause() == UnderdeterminedCause.NO_DEFAULT_BRANCH
    }

    // FR4: the reason names what was consulted, so a report reads without the code beside it
    def "each tier explains itself"() {
        expect:
        reasonOf(request(explicitBase: 'v1.2.3')) == 'explicit --base argument'
        reasonOf(request(designator: BaseDesignator.single('release/1.18')))
        == "the task's base designator, accepted by allowed-base pattern 'release/*'"
        reasonOf(request(configuredDefault: 'develop')) == 'the configured task-branch.base.default'
        reasonOf(request(defaultBranch: 'trunk')) == 'the repository default branch reported by the remote'
        reasonOf(request(mode: ResolutionMode.MANUAL)) == "a manual run without --base: the clone's local HEAD"
    }

    // FR10, NFR-C1: deterministic — two instances with the same inputs produce the same decision
    def "the same inputs always produce the same decision"() {
        given:
        def inputs = request(designator: BaseDesignator.single('release/1.18'), defaultBranch: 'trunk')

        expect:
        BaseRefResolver.resolve(inputs) == BaseRefResolver.resolve(inputs)
        BaseRefResolver.resolve(inputs).decision()
                == new BaseDecision(
                'release/1.18',
                BaseRule.DESIGNATOR,
                "the task's base designator, accepted by allowed-base pattern 'release/*'")
    }

    // FR7: the rule vocabulary is declared in priority order — the order is the specification
    def "the rule vocabulary is the priority order, highest first, plus the forward-compat UNKNOWN fold target"() {
        expect:
        BaseRule.values() as List == [
            BaseRule.EXPLICIT_ARGUMENT,
            BaseRule.DESIGNATOR,
            BaseRule.CONFIGURED_DEFAULT,
            BaseRule.REPOSITORY_DEFAULT_BRANCH,
            BaseRule.LOCAL_HEAD,
            BaseRule.UNKNOWN,
        ]

        and:
        UnderdeterminedCause.values() as List == [
            UnderdeterminedCause.DESIGNATOR_NOT_ALLOWED,
            UnderdeterminedCause.DESIGNATOR_CONFLICT,
            UnderdeterminedCause.NO_DEFAULT_BRANCH,
        ]

        and:
        ResolutionMode.values() as List == [
            ResolutionMode.MANUAL,
            ResolutionMode.AUTONOMOUS
        ]
    }

    private static String reasonOf(BaseRefRequest input) {
        BaseRefResolver.resolve(input).decision().reason()
    }
}
