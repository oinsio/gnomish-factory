package com.github.oinsio.gnomish.logtext;

/**
 * The factory's operator-event catalog (FR14 of harden-logging-observability): one constant per
 * production WARN/ERROR call site, each owning a stable {@code [GFnnn]} code that the site renders
 * as its message head.
 *
 * <p><strong>The code is the contract, the prose is not.</strong> An operator's alert, an
 * operator's grep and a spec's assertion all key on the code, so wording may be rewritten freely
 * without breaking any of them. That is the whole point of the indirection: before it, the only
 * identity a WARN line had was its sentence, and every test that asserted one froze it.
 *
 * <p>Three rules keep the catalog usable as a contract:
 *
 * <ul>
 *   <li><b>One code, one call site.</b> Two emitters of the same fault take two constants — the
 *       code names <em>where</em> the factory degraded, not merely what kind of thing happened.
 *   <li><b>Codes are never reused.</b> A retired line's constant is deleted, its number is not
 *       handed to anything else; a new line takes the next free number.
 *   <li><b>Operator plane only.</b> WARN and ERROR carry codes; INFO and DEBUG never do. Extending
 *       the catalog downward would make every diagnostic line a versioned interface.
 * </ul>
 *
 * <p>Kept in sync with the four {@code :domain} emitters of ADR 0004's accepted deviation 1
 * ({@code AttemptJournal}, {@code Events}, {@code RoundExecution}, {@code VerifyOrchestrator}):
 * they cannot reach {@code :logtext} without giving the domain the module edge it exists to
 * refuse, so each repeats its code as a literal message head. What must stay in sync is the code
 * itself — the constants {@code ATTEMPT_PERSIST_FAILED}, {@code ENGINE_EVENT_LISTENER_THREW},
 * {@code EXECUTOR_THREW} and {@code CHECK_ADAPTER_THREW} and the literals those classes spell out.
 * Neither end can name the other with a resolvable link, so the pair is listed in {@code
 * .claude/rules/manual-sync-pairs.md} and pinned by {@code DomainOperatorEventHeadSpec}. A static gate (FR16) fails the build on an uncoded site, a duplicated code or a
 * code no test source names; a runtime gate (FR17) fails a spec that provokes an unasserted one.
 *
 * <p>Implements FR14 of harden-logging-observability.
 */
public enum OperatorEvent {
    // agent executor and judge adapters
    AGENT_PROGRESS_LISTENER_THREW("GF001"),
    COMPOSITE_PROGRESS_LISTENER_THREW("GF002"),
    DECISION_FILE_EMPTY("GF003"),
    DECISION_FILE_NOT_JSON("GF004"),
    ROUND_DENIALS_UNREADABLE_ON_FINISH("GF005"),
    ROUND_DENIALS_ORPHANED_ON_FAILURE("GF006"),
    ROUND_DENIALS_UNREADABLE_ON_FAILURE("GF007"),
    JUDGE_CRITERIA_UNREADABLE("GF008"),
    JUDGE_CANNOT_VERIFY_BY_DECISION("GF009"),
    JUDGE_CANNOT_VERIFY_BY_THROWABLE("GF010"),
    JUDGE_VERDICT_UNEXTRACTABLE("GF011"),
    TOKEN_USAGE_UNREPORTED("GF012"),
    // git adapter
    PUSH_SKIPPED_HEAD_OFF_BRANCH("GF013"),
    PUSH_SKIPPED_TIP_NOT_ANCESTOR("GF014"),
    PUSH_FAILED("GF015"),
    BRANCH_PUSH_FAILED("GF016"),
    SALVAGE_PROBE_UNREACHABLE("GF017"),
    SALVAGE_SKIPPED_ENVIRONMENT_LOST("GF018"),
    DISCARD_SKIPPED_ENVIRONMENT_LOST("GF019"),
    SALVAGE_HARVEST_FAILED("GF020"),
    GIT_NETWORK_COMMAND_TIMED_OUT("GF021"),
    GIT_COMMAND_KILLED("GF022"),
    LIFECYCLE_PUSH_FAILED("GF023"),
    MID_ROUND_POLL_SKIPPED("GF024"),
    MID_ROUND_POLL_SKIPPED_ROLLUP("GF025"),
    ORIGIN_RECONCILIATION_SKIPPED("GF026"),
    ORIGIN_RECONCILIATION_FAILED("GF027"),
    PARK_FENCE_TIP_UNREADABLE("GF028"),
    PARK_FENCE_EXHAUSTED("GF029"),
    PARK_FENCE_INTERRUPTED("GF030"),
    PARK_FENCE_TIMED_OUT_ORIGIN_BEHIND("GF031"),
    PARK_FENCE_TIMED_OUT_ORIGIN_SILENT("GF032"),
    REPLICA_RESET_LOST_CAS("GF033"),
    REPLICA_LOCAL_BRANCH_DISCARDED("GF034"),
    WORKTREE_REMOVE_FAILED("GF035"),
    USAGE_HISTORY_LISTING_FAILED("GF036"),
    USAGE_HISTORY_COMMIT_UNREADABLE("GF037"),
    WORKTREE_DISCARD_STEP_FAILED("GF038"),
    // GitHub tracker and check adapters
    GITHUB_WORKFLOW_CANNOT_VERIFY("GF039"),
    GITHUB_WORKFLOW_CANNOT_VERIFY_ROLLUP("GF040"),
    CLAIM_COMMENT_DELETE_FAILED("GF041"),
    MARKER_COMMENT_DROPPED("GF042"),
    // check, http and secrets adapters
    COMMAND_CHECK_TIMED_OUT("GF043"),
    COMMAND_CHECK_INTERRUPTED("GF044"),
    FINDINGS_FILE_MISSING_ARRAY("GF045"),
    FINDINGS_FILE_BLANK_ENTRY("GF046"),
    FINDINGS_FILE_MALFORMED("GF047"),
    EXTERNAL_CHECK_PIN_MISMATCH("GF048"),
    COMMAND_CHECK_NO_ENVIRONMENT("GF049"),
    COMMAND_CHECK_PROCESS_START_FAILED("GF050"),
    HTTP_CHECK_REFUSED_BY_EGRESS("GF051"),
    SECRET_FILE_UNREADABLE("GF052"),
    // application: commands, lease, serve, take
    CONTEXT_CLOSE_UNCLEAN("GF053"),
    RUN_UNHANDLED_EXCEPTION("GF054"),
    SERVE_TRACKER_PROVISION_FAILED("GF055"),
    BATCH_TAKE_REF_TOOL_ERROR("GF056"),
    BRANCH_REPAIR_REPEATED("GF057"),
    HEARTBEAT_BEAT_FAILED("GF058"),
    HEARTBEAT_THREAD_STOPPED_BY_SHUTDOWN("GF059"),
    HEARTBEAT_THREAD_DIED("GF060"),
    HEARTBEAT_TICK_FAILED("GF061"),
    HEARTBEAT_STATE_LISTENER_FAILED("GF062"),
    CLAIM_UNCONFIRMED_WRITES_FROZEN("GF063"),
    REAPER_SWEEP_LISTING_FAILED("GF064"),
    REAPER_FOREIGN_BRANCH_UNOWNED("GF065"),
    REAPER_REPAIR_FAILED("GF066"),
    STANDING_REAPER_TICK_FAILED("GF067"),
    STANDING_REAPER_WORKER_DIED("GF068"),
    STANDING_REAPER_BACKOFF_SLEEP_FAILED("GF069"),
    DIRTY_NOTIFIER_FAILED("GF070"),
    FEED_CANDIDATE_OCCUPIES_SLOT("GF071"),
    FEED_TRACKER_OUTAGE_SUSPECTED("GF072"),
    SANDBOX_LIFECYCLE_TICK_FAILED("GF073"),
    SLOT_SKIPPED("GF074"),
    SLOT_STOPPED_BY_SHUTDOWN("GF075"),
    SLOT_CRASHED_UNCAUGHT("GF076"),
    WORKTREE_JANITOR_TICK_FAILED("GF077"),
    WORKTREE_JANITOR_SCAN_FAILED("GF078"),
    WORKTREE_JANITOR_REF_UNSANITARY("GF079"),
    INFRASTRUCTURE_ABORT("GF080"),
    ABORT_PARK_FAILED("GF081"),
    ABORT_RECORD_FAILED("GF082"),
    DECISION_ACK_UNVERIFIED("GF083"),
    FINISH_LANDING_UNVERIFIED("GF084"),
    FINISH_SKIPPED_CLAIM_LOST("GF085"),
    FINISH_UNWRITTEN_AFTER_RETRIES("GF086"),
    DECLINE_FINISHED_FAILED("GF087"),
    PARK_LANDING_UNVERIFIED("GF088"),
    PARK_SKIPPED_CLAIM_LOST("GF089"),
    PARK_UNWRITTEN_AFTER_RETRIES("GF090"),
    RECORD_PROGRESS_FAILED("GF091"),
    ABORT_FACTS_UNREADABLE("GF092"),
    TASK_QUARANTINED("GF093"),
    QUARANTINE_PARK_FAILED("GF094"),
    // dashboard
    SWEEP_ACTION_LEDGER_UNAGGREGATABLE("GF095"),
    OUTCOME_LEDGER_UNAGGREGATABLE("GF096"),
    DASHBOARD_RENDER_WRITE_FAILED("GF097"),
    DAEMON_SNAPSHOT_UNREADABLE("GF098"),
    // observability writers
    LEDGER_RETENTION_LIST_FAILED("GF099"),
    LEDGER_RETENTION_DELETE_FAILED("GF100"),
    LIFECYCLE_LEDGER_APPEND_FAILED("GF101"),
    RUN_SUMMARY_LEDGER_APPEND_FAILED("GF102"),
    SNAPSHOT_WRITE_FAILED("GF103"),
    SNAPSHOT_RETENTION_SWEEP_FAILED("GF104"),
    SNAPSHOT_TICK_FAILED("GF105"),
    SWEEP_LEDGER_APPEND_FAILED("GF106"),
    TASK_OUTCOME_SLOT_MISSING("GF107"),
    TASK_OUTCOME_LEDGER_APPEND_FAILED("GF108"),
    // status anchors
    TASK_SUMMARY_WORTH_LOOKING_AT("GF109"),
    // domain engine (ADR 0004 accepted deviation 1: literal heads, no :logtext edge)
    ATTEMPT_PERSIST_FAILED("GF110"),
    ENGINE_EVENT_LISTENER_THREW("GF111"),
    EXECUTOR_THREW("GF112"),
    CHECK_ADAPTER_THREW("GF113"),
    // sandbox backend
    CONTAINER_CHANNEL_FILE_TRUNCATED("GF114"),
    DOCKER_COMMAND_TIMED_OUT("GF115"),
    DOCKER_COMMAND_KILLED("GF116"),
    EGRESS_GUARD_RECREATED("GF117"),
    GUARD_DENIAL_LOG_TRUNCATED("GF118"),
    GUARD_DENIAL_EVENTS_DROPPED("GF119"),
    GUARD_DENIAL_LOG_UNREADABLE("GF120"),
    GUARD_DENIAL_LOG_READ_FAILED("GF121"),
    GUARD_DENIAL_TAIL_WINDOW_FULL("GF122"),
    HOST_CHANNEL_FILE_TRUNCATED("GF123"),
    SCRATCH_AREA_REMOVAL_INCOMPLETE("GF124"),
    SCRATCH_AREA_ENTRIES_LEFT("GF125");

    private final String code;
    private final String head;

    OperatorEvent(String code) {
        this.code = code;
        this.head = "[" + code + "] ";
    }

    /** This event's stable code, {@code GFnnn} — the identity an alert or a grep keys on. */
    public String code() {
        return code;
    }

    /**
     * The message head this event's call site prepends to its format string, {@code "[GFnnn] "} —
     * trailing space included, so a site concatenates the head and its sentence and nothing else.
     */
    public String head() {
        return head;
    }
}
