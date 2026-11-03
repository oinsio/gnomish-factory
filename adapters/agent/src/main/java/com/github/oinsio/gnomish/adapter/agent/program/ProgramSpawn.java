package com.github.oinsio.gnomish.adapter.agent.program;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory;
import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.PendingKey;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One operation of a {@code program} executor — a {@code start} or a {@code poll} — spawned and read
 * (design D3 of define-executor-contract): the declared argv with the operation word appended, the
 * request as standard input — the command's stdin, which the launch owner gives the child as a file
 * — two fresh factory-named paths under the environment's scratch area, {@code GNOMISH_RESULT_FILE}
 * and {@code GNOMISH_ANSWER_FILE}, and for a poll the pending key in {@code GNOMISH_PENDING_KEY}, with
 * the credentials the law grants the program as its own resolved layer (ADR 0017) and nothing else
 * beyond the environment's base. It is spawned only through the environment's process seam
 * ({@link ProgramInvocation}); its stderr goes to a host file whose tail is quoted by a failure and
 * is one DEBUG line otherwise; its result is read through the one result reader ({@link
 * ProgramResultRead}). Both operations run under the same bounds and read the same way, so a poll
 * cannot be addressed or read differently from the start it continues.
 *
 * <p>Implements FR5, FR6, FR23, NFR-R2, NFR-S1 of define-executor-contract.
 */
final class ProgramSpawn {

    private final ExecutorDeclaration declaration;
    private final boolean streamsProgress;
    private final List<String> credentials;
    private final TaskExecutionEnvironment environment;
    private final ProgramRoundEquipment equipment;

    /**
     * @param declaration the law's declaration of the executor; never null
     * @param streamsProgress whether its describe declared {@code streams_progress}
     * @param credentials the credential names the law grants it; never null
     * @param environment where its operations execute; materialized; never null
     * @param equipment the bounds, time, credential grant and stderr parent; never null
     */
    ProgramSpawn(
            ExecutorDeclaration declaration,
            boolean streamsProgress,
            List<String> credentials,
            TaskExecutionEnvironment environment,
            ProgramRoundEquipment equipment) {
        this.declaration = declaration;
        this.streamsProgress = streamsProgress;
        this.credentials = List.copyOf(credentials);
        this.environment = environment;
        this.equipment = equipment;
    }

    /**
     * Spawns one operation and reads its result; never throws for what the program did.
     *
     * @param operation {@link ProgramChannel#START} or {@link ProgramChannel#POLL}
     * @param request the request, the child's standard input; never null
     * @param key the pending key a poll continues, {@code null} for a start
     * @return the operation's result, or the factory's {@code failed/infrastructure}; never null
     */
    ExecutorResult spawn(String operation, ExecutorRequest request, @Nullable PendingKey key) {
        String name = declaration.name();
        String resultFile = ProgramChannel.freshPath(environment, name, "result");
        String answerFile = ProgramChannel.freshPath(environment, name, "answer");
        Map<String, String> variables = new LinkedHashMap<>(equipment.grant().grant(credentials));
        variables.put(ProgramChannel.RESULT_FILE_VARIABLE, resultFile);
        variables.put(ProgramChannel.ANSWER_FILE_VARIABLE, answerFile);
        if (key != null) {
            variables.put(ProgramChannel.PENDING_KEY_VARIABLE, key.value());
        }
        List<String> argv = ProgramChannel.argv(declaration, operation);
        try (RoundScratchDirectory stderr =
                RoundScratchDirectory.open(equipment.stderrParent(), RoundScratchDirectory.Writer.PROGRAM)) {
            var context = new ProgramResultRead.FailureContext(name, stderr);
            ProgramEnd end;
            try {
                end = new ProgramInvocation(environment, equipment.bounds(), equipment.time())
                        .run(
                                name,
                                new ExecCommand(argv, variables, rendered(request), stderr.stderr()),
                                streamsProgress);
            } catch (ProcessStartException refused) {
                return context.failure("the environment refused to start the program: "
                        + UntrustedText.subprocess(String.valueOf(refused.getMessage()))
                                .excerpt(512));
            }
            ExecutorResult result =
                    switch (end) {
                        case ProgramEnd.Exited exited ->
                            ProgramResultRead.read(environment, resultFile, answerFile, exited, context);
                        case ProgramEnd.BoundExpired expired ->
                            context.failure("the " + expired.bound() + " bound of " + expired.value()
                                    + " expired and its process tree was killed");
                        case ProgramEnd.Interrupted ignored ->
                            context.failure("the run was interrupted and its process tree killed");
                    };
            if (result.status() == Status.COMPLETED) {
                stderr.logTailOnSuccess();
            }
            return result;
        }
    }

    private static String rendered(ExecutorRequest request) {
        try {
            return ExecutorContractJson.writer().writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the factory could not render its own executor request", e);
        }
    }
}
