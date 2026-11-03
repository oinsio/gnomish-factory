package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateAttemptDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.app.port.git.UsageRow;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rounds one {@code gnomish usage} walk has met, keyed by the identity each round record carries
 * (design D13, FR14, FR19 of define-executor-contract): {@code taskId}, {@code stage}, round token and
 * round number, written by the round's writer. A record is a new row the first time its identity
 * appears on the walk and never again, so a commit that rewrites {@code state.json} without
 * recording a round (a decision's reset, a no-op rewrite) adds nothing, and a round a later commit
 * still lists is not counted twice. Nothing is inferred from the position, the list's shape or a
 * neighbouring record: the row's stage is the record's.
 *
 * <p>A record whose {@code taskId} is not the walked task's is rejected with one warning naming
 * the commit it was first met at, and is never a row — the content check, the second defence
 * beside the walk's {@code baseCommit..register} first-parent range.
 *
 * <p>One instance per walk; not thread-safe, used on the walking thread only.
 *
 * <p>Implements FR14, FR19 of define-executor-contract.
 */
final class UsageRoundRows {

    private static final Logger log = LoggerFactory.getLogger(UsageRoundRows.class);

    private final String taskId;
    private final Set<RecordIdentity> met = new HashSet<>();

    /** @param taskId the walked task's id, as its {@code task.json} records it */
    UsageRoundRows(String taskId) {
        this.taskId = taskId;
    }

    /**
     * The rows {@code state} adds to the walk: its records whose identity this walk has not met
     * yet, in the order the state lists them, foreign records rejected.
     *
     * @param commit the commit {@code state} was read at, named by a rejection's warning
     * @param state the {@code state.json} read at {@code commit}
     */
    List<UsageRow> rowsOf(String commit, StateJsonDto state) {
        List<UsageRow> rows = new ArrayList<>();
        for (StateAttemptDto record : state.attempts()) {
            if (!met.add(identityOf(record))) {
                continue;
            }
            if (!taskId.equals(record.taskId())) {
                log.warn(
                        OperatorEvent.USAGE_HISTORY_FOREIGN_RECORD.head()
                                + "usage: commit {} records a round of task {} on the branch of task {}; the round"
                                + " is not counted",
                        commit,
                        UntrustedText.branchDocument(record.taskId()).forLog(),
                        UntrustedText.branchDocument(taskId).forLog());
                continue;
            }
            rows.add(new UsageRow(StateJsonMapper.fromAttempt(record)));
        }
        return rows;
    }

    private static RecordIdentity identityOf(StateAttemptDto record) {
        return new RecordIdentity(record.taskId(), record.stage(), record.roundToken(), record.round());
    }

    /** The identity a round record carries, as written. */
    private record RecordIdentity(
            String taskId, String stage, @Nullable String roundToken, int round) {}
}
