# The gnome's round, as stream-json on stdout. GNOME_BARE_REPO names a file holding the bare
# remote's path; the two tips are written to the files GNOME_COMMITTED_TIP and GNOME_OBSERVED_TIP name.
set -eu
bare=$(cat "$GNOME_BARE_REPO")
echo '{"type":"system","subtype":"init","session_id":"fake-session-1","model":"claude-fake-main-1","cwd":"/workspace","tools":["Bash"]}'
echo '{"type":"assistant","session_id":"fake-session-1","message":{"id":"msg_1","model":"claude-fake-main-1","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"git commit"}}]}}'
echo 'gnome mid-round work' > gnome.txt
git add gnome.txt >/dev/null 2>&1
git -c user.email=g@b.c -c user.name=gnome commit -q -m 'gnome mid-round commit'
git rev-parse HEAD > "$GNOME_COMMITTED_TIP"
echo '{"type":"assistant","session_id":"fake-session-1","message":{"id":"msg_2","model":"claude-fake-main-1","content":[{"type":"tool_use","id":"toolu_2","name":"Bash","input":{"command":"true"}}]}}'
local_tip=$(cat "$GNOME_COMMITTED_TIP")
i=0
remote_tip=none
while [ $i -lt 150 ]; do
    remote_tip=$(git --git-dir="$bare" rev-parse refs/heads/gnomish/PROJ-1 2>/dev/null || echo none)
    [ "$remote_tip" = "$local_tip" ] && break
    i=$((i+1))
    sleep 0.1
done
printf '%s' "$remote_tip" > "$GNOME_OBSERVED_TIP"
echo '{"type":"result","subtype":"success","session_id":"fake-session-1","result":"Stage complete.","usage":{"input_tokens":120,"output_tokens":45,"cache_creation_input_tokens":10,"cache_read_input_tokens":5},"modelUsage":{"claude-fake-main-1":{"inputTokens":120,"outputTokens":45,"cacheCreationInputTokens":10,"cacheReadInputTokens":5}}}'
