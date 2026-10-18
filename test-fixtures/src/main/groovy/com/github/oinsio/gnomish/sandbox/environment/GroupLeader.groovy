package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path

/**
 * A real process group a spec starts, owns and ends (design D19 of fix-terminal-receipt-convergence):
 * the stand-in preset {@code group-leader} launched through the launch owner, returned only once it
 * leads its group — the launcher calls {@code setsid} before the exec, so the line the preset prints
 * first proves the group exists, and its stall keeps the pid. A spec names its pid as a
 * {@link FakeProcess}'s ({@link FakeProcess#leading}), so the group sweep the code under test runs
 * after that fake's exit reaches this group and nothing else; the spec destroys it in its cleanup if
 * the sweep did not.
 */
final class GroupLeader {

    private GroupLeader() {}

    /**
     * @param inputDirectory the launch owner's input directory, a spec's temporary one
     * @return the leader, alive and leading its group
     */
    static Process start(Path inputDirectory) {
        ProcessBuilder builder = new ProcessBuilder(StandIn.process('group-leader').toString())
        TestChildEnvironment.cleared(builder)
        Process leader = ProcessLaunch.grouped(inputDirectory).start(builder, StdinSource.NONE)
        String ready = new BufferedReader(new InputStreamReader(leader.inputStream)).readLine()
        assert ready == StandIn.data('stdout-process#leading').trim()
        leader
    }
}
