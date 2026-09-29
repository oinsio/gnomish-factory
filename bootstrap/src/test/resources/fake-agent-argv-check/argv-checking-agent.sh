#!/bin/sh
# In-box stand-in for the agent CLI that reads back the argv each role was launched with
# (M1 of fix-operator-blockers, container mode) — see FakeAgentSandboxImage.ensureBuiltCheckingArgv.
#
# The judge model id is the first line of /opt/gnomish-fake/judge-model.
#   - an executor round plays plain-round and appends its argv to fake-agent-argv.txt in its
#     working copy, which the factory harvests into the snapshot commit;
#   - a judge vote checks its own argv: dontAsk + --strict-mcp-config and no bypassPermissions
#     plays judge-verdict-pass, anything else prints a failing verdict quoting the argv.
judge_model=$(head -n 1 /opt/gnomish-fake/judge-model)
previous=''
judge=no
mode=''
strict=no
bypass=no
for arg in "$@"; do
    if [ "$previous" = '--model' ] && [ "$arg" = "$judge_model" ]; then judge=yes; fi
    if [ "$previous" = '--permission-mode' ]; then mode="$arg"; fi
    if [ "$arg" = '--strict-mcp-config' ]; then strict=yes; fi
    if [ "$arg" = 'bypassPermissions' ]; then bypass=yes; fi
    previous="$arg"
done

if [ "$judge" = no ]; then
    export GNOMISH_FAKE_SCENARIO=plain-round
    export GNOMISH_FAKE_CAPTURE_ARGV="$PWD/fake-agent-argv.txt"
    exec sh /opt/gnomish-fake/fake-agent.sh "$@"
fi

if [ "$mode" = 'dontAsk' ] && [ "$strict" = yes ] && [ "$bypass" = no ]; then
    export GNOMISH_FAKE_SCENARIO=judge-verdict-pass
    exec sh /opt/gnomish-fake/fake-agent.sh "$@"
fi

# The prompt arrives on stdin; drain it so the factory's writer never blocks.
cat > /dev/null
# Quotes and backslashes are dropped so the argv can sit inside the doubly encoded verdict.
argv=$(printf '%s ' "$@" | tr -d '"\\\n')
finding="judge argv lacks --permission-mode dontAsk or --strict-mcp-config: $argv"
printf '{"type":"result","subtype":"success","session_id":"fake-argv-check","result":"{\\"passed\\": false, \\"findings\\": [\\"%s\\"]}","usage":{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}\n' "$finding"
