#!/bin/sh
# In-box stand-in for the agent CLI that records which agent credentials its round received
# (FR5, NFR-S3 of fix-operator-blockers, container mode) — see
# FakeAgentSandboxImage.ensureBuiltCapturingCredentials. The two values are written into the
# working copy, which the factory harvests into the snapshot commit; an unset variable is written
# as an empty value. Then the round plays plain-round as usual.
printf 'CLAUDE_CODE_OAUTH_TOKEN=%s\nANTHROPIC_API_KEY=%s\n' \
    "${CLAUDE_CODE_OAUTH_TOKEN:-}" "${ANTHROPIC_API_KEY:-}" > "$PWD/fake-agent-credentials.txt"
exec sh /opt/gnomish-fake/fake-agent.sh "$@"
