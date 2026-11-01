package com.github.oinsio.gnomish.app.port.agent;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * The decision request one round's agent wrote to its decision file, as the tolerant read made it
 * ({@link DecisionRequestRead}): the question and the offered options, in file order. A file the
 * read could not parse arrives here as its raw text for a question with no options, so nothing the
 * agent wrote is lost.
 *
 * <p>Both components are {@link UntrustedText}: the decision file is written by the agent, and the
 * pair is published to the tracker for a human to answer (design D3, D4 of type-untrusted-text).
 *
 * <p>Implements FR3 of add-agent-executor; FR6 of define-executor-contract.
 *
 * @param question the question text; never null
 * @param options the offered options, in file order; never null, possibly empty
 */
public record RoundDecisionRequest(UntrustedText question, List<UntrustedText> options) {}
