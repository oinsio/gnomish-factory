package com.github.oinsio.gnomish.domain.pipeline

import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import spock.lang.Specification

/**
 * FR13 of extract-agent-round-runner (design D12): a {@code command} judge block is refused by the
 * command built-in's owned roles, {@link BuiltInExecutors#COMMAND_ROLES}, never by a second spelling —
 * so a built-in that came to serve {@code verdict} would be admitted, and the refusal names the roles
 * it serves. The located error over today's roles is {@code StageSanityRuleSpec}'s.
 */
class JudgeRoleRuleSpec extends Specification {

    def "FR13: a command built-in serving #roles is #outcome"() {
        expect:
        JudgeRoleRule.commandReason(roles) == reason

        where:
        roles || reason
        [ExecutorRole.TRANSFORM] || "executor type 'command' resolves to the built-in executor 'command', which serves only role 'transform'"
        [
            ExecutorRole.TRANSFORM,
            ExecutorRole.EFFECT
        ] || "executor type 'command' resolves to the built-in executor 'command', which serves only role 'transform', 'effect'"
        [
            ExecutorRole.TRANSFORM,
            ExecutorRole.VERDICT
        ] || null

        outcome = reason == null ? 'admitted' : 'refused, naming its roles'
    }
}
