package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.domain.branch.EnvelopePath;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.Map;

/**
 * The in-branch answer file of a round in a git mode (FR9 of extract-agent-round-runner, design D3;
 * FR23 of add-sandbox-core, design D17): the round's decision request is a {@code
 * decision-request} answer written to {@link EnvelopePaths#answerFile} <em>inside the working
 * copy</em> — the single gnome-writable path under {@code .gnomish-task/} (the admission's
 * carve-out, {@link AdmissionRule}, names exactly this path). Riding the snapshot/salvage commit, a
 * pending escalation survives any death and any instance resumes it from the branch alone; the
 * file reaches the factory over the hardened harvest path and is visible in the PR during
 * escalation.
 *
 * <p>The {@code $GNOMISH_ANSWER_FILE} value ({@link ProgramChannel#ANSWER_FILE_VARIABLE}, the
 * contract's one answer-file variable) is the working-copy-relative path: every adapter runs the
 * agent with the working copy as its working directory, and both adapters' file channels anchor
 * relative paths there, so no adapter-private absolute path leaks into the protocol. A request is
 * live only under its round's token (design D10 of make-checkpoint-gate-durable): a stage and
 * attempt repeat across visits of a stage, so each round reads exactly the path its token fixes
 * and nothing else. The outcome-clearing commits remove {@code .gnomish-task/decisions/} as hygiene
 * (FR14), but no read relies on that removal — the token match alone decides liveness.
 *
 * <p>This class names the file and reads nothing: the live round reads it, after the agent exited
 * and before persist, through the one answer read ({@code AnswerFileRead}) over the round's
 * environment channel at {@link Handle#relativePath}, and a resume of a snapshot's tree reads the
 * same path through the same read ({@link SnapshotTipCheck}; FR21, NFR-R5 of
 * extract-agent-round-runner, design D15).
 *
 * <p>Implements FR23 of add-sandbox-core; FR13, FR16 of make-checkpoint-gate-durable; FR6 of
 * define-executor-contract; FR9, FR21 of extract-agent-round-runner.
 */
public final class BranchDecisionFile {

    private BranchDecisionFile() {}

    /**
     * Opens one round's in-branch answer file.
     *
     * @param key the round's key; with {@code token}, fixes the file name
     * @param token the round's token (FR13 of make-checkpoint-gate-durable); the one path read is
     *     named by it, so a file under any other token — carried over on the tip, left by a killed
     *     round, or written beside the real one — is never read
     * @return the round's handle; never null
     */
    public static Handle open(AttemptKey key, RoundToken token) {
        return new Handle(EnvelopePaths.answerFile(key, token));
    }

    /** One round's in-branch answer file: the path and its env fragment. */
    public static final class Handle {

        private final EnvelopePath file;

        private Handle(EnvelopePath file) {
            this.file = file;
        }

        /** The working-copy-relative answer file, e.g. {@code .gnomish-task/decisions/implement-a1-<token>.json}. */
        public String relativePath() {
            return file.relative();
        }

        /** The env fragment naming the answer file for the launched agent process. */
        public Map<String, String> envFragment() {
            return Map.of(ProgramChannel.ANSWER_FILE_VARIABLE, file.relative());
        }
    }
}
