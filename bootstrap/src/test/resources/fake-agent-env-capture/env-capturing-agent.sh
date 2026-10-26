#!/bin/sh
# In-box stand-in for the agent CLI that records which agent credentials its round received
# (FR5, NFR-S3 of fix-operator-blockers, container mode) — see
# FakeAgentSandboxImage.ensureBuiltCapturingCredentials. The two values are written into the
# working copy, which the factory harvests into the snapshot commit; an unset variable is written
# as an empty value. The two passthrough probes of ContainerModeAgentCredentialE2ESpec (FR2 of
# fix-docker-exec-env-argv) follow, as NAME=[value] when set and NAME=unset otherwise, so an
# empty value and an unset variable stay distinct. Then the round plays plain-round as usual.
out="$PWD/fake-agent-credentials.txt"
printf 'CLAUDE_CODE_OAUTH_TOKEN=%s\nANTHROPIC_API_KEY=%s\n' \
    "${CLAUDE_CODE_OAUTH_TOKEN:-}" "${ANTHROPIC_API_KEY:-}" > "$out"
if [ "${GF_E2E_PASSTHROUGH_PRESENT+set}" = set ]; then
    printf 'GF_E2E_PASSTHROUGH_PRESENT=[%s]\n' "$GF_E2E_PASSTHROUGH_PRESENT" >> "$out"
else
    printf 'GF_E2E_PASSTHROUGH_PRESENT=unset\n' >> "$out"
fi
if [ "${GF_E2E_PASSTHROUGH_ABSENT+set}" = set ]; then
    printf 'GF_E2E_PASSTHROUGH_ABSENT=[%s]\n' "$GF_E2E_PASSTHROUGH_ABSENT" >> "$out"
else
    printf 'GF_E2E_PASSTHROUGH_ABSENT=unset\n' >> "$out"
fi
exec sh /opt/gnomish-fake/fake-agent.sh "$@"
