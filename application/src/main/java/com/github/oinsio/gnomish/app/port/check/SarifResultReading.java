package com.github.oinsio.gnomish.app.port.check;

import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.ResultLevel;
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer;
import com.github.oinsio.gnomish.executorcontract.SarifResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * The one reading of a SARIF result onto the engine's verdict answer (design D18, D19 of
 * migrate-verify-to-executor-contract; the D9 row of the contract → domain mapping): its level one to
 * one, and its texts as a {@link Finding} — the message, the location as the URI with its start line
 * when the result names them, and the result's own details ({@code properties.details}) as the
 * finding's details, apart from the message. One consumer: the contract → domain mapping of a
 * verdict round's answer ({@code ProgramResultMapping}, in {@code adapters/agent}), the {@code shell}
 * built-in's answer included; it lives here, in {@code app.port.check}, because it was extracted from
 * the {@code shell} check's former engine-port path in {@code :adapters}, which cannot reach {@code
 * adapters/agent}, and both reach this module.
 *
 * <p>An {@link UntrustedExit}: the domain {@link Finding} holds plain text, so the result's texts
 * leave their carriers here — the {@code CheckFindings} precedent.
 *
 * <p>Implements FR5, FR7 of migrate-verify-to-executor-contract.
 */
@UntrustedExit
public final class SarifResultReading {

    private SarifResultReading() {}

    /**
     * The engine's reading of one SARIF result.
     *
     * @param result the SARIF result; never null
     * @return the result as the verdict derivation reads it; never null
     */
    public static VerdictAnswer.Result toDomain(SarifResult result) {
        ResultLevel level =
                switch (result.level()) {
                    case NONE -> ResultLevel.NONE;
                    case NOTE -> ResultLevel.NOTE;
                    case WARNING -> ResultLevel.WARNING;
                    case ERROR -> ResultLevel.ERROR;
                };
        UntrustedText uri = result.uri();
        String location =
                uri == null ? null : result.startLine() == null ? uri.raw() : uri.raw() + ":" + result.startLine();
        return new VerdictAnswer.Result(
                level, new Finding(result.message().raw(), location, rawOrNull(result.details())));
    }

    private static @Nullable String rawOrNull(@Nullable UntrustedText text) {
        return text == null ? null : text.raw();
    }
}
