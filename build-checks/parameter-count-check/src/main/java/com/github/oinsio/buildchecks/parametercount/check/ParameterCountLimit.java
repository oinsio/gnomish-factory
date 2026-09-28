package com.github.oinsio.buildchecks.parametercount.check;

import static com.google.errorprone.BugPattern.LinkType.NONE;
import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;

import com.github.oinsio.buildchecks.parametercount.ParameterLimitExemption;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MethodTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;

/**
 * Fails compilation on a constructor or method with more than seven parameters — the
 * parameter-count rule of {@code process-invariants.md}, made mechanical.
 *
 * <p>Two exemptions are decided from the syntax tree, never from a list: a record's
 * constructors, since the record is itself the parameter object the rule asks for (an ordinary
 * method housed in a record is counted like any other), and a method that overrides or
 * implements another, since it does not choose its own signature. A constructor javac writes (an
 * anonymous class's) is never visited by Error Prone's scan, so it needs no branch here.
 *
 * <p>Every other exception is one declaration carrying {@link ParameterLimitExemption} with a
 * non-blank reason. The annotation is read here, on the declaration being judged, rather than
 * handed to Error Prone's suppression mechanism: that mechanism silences the whole subtree of
 * the annotated element — the local and anonymous classes declared inside it included — and
 * never lets the check see the annotation, so a blank reason could not be reported. The
 * {@code suppressionAnnotations} set is therefore empty, which also leaves
 * {@code @SuppressWarnings} inert for this check: no bulk form of exemption exists. An annotation
 * on a declaration that would pass without it is reported as unnecessary, so a signature shrunk
 * under the limit cannot leave a stale entry in the list of exceptions.
 *
 * <p>Implements FR1, FR2, FR3, FR4 and NFR-O1 of add-parameter-count-gate (design D1, D3, D5).
 */
@BugPattern(
        summary = "A constructor or method takes more than seven parameters",
        severity = ERROR,
        linkType = NONE,
        suppressionAnnotations = {})
public final class ParameterCountLimit extends BugChecker implements MethodTreeMatcher {

    static final int LIMIT = 7;

    private static final String EXEMPTION = ParameterLimitExemption.class.getCanonicalName();

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
        var symbol = ASTHelpers.getSymbol(tree);
        int count = tree.getParameters().size();
        boolean violates = violatesLimit(symbol, count, state);
        var reason = exemptionReason(symbol);
        if (reason.isPresent()) {
            if (reason.get().isBlank()) {
                return buildDescription(tree).setMessage(blankReasonMessage()).build();
            }
            return violates
                    ? Description.NO_MATCH
                    : buildDescription(tree).setMessage(unnecessaryExemptionMessage()).build();
        }
        return violates ? buildDescription(tree).setMessage(overLimitMessage(count)).build() : Description.NO_MATCH;
    }

    /** Whether the declaration fails the limit when judged without its exemption annotation. */
    private static boolean violatesLimit(MethodSymbol symbol, int count, VisitorState state) {
        if (count <= LIMIT) {
            return false;
        }
        if (symbol.isConstructor() && symbol.owner.getKind() == ElementKind.RECORD) {
            return false;
        }
        return ASTHelpers.findSuperMethods(symbol, state.getTypes()).isEmpty();
    }

    /** The {@code reason} of the declaration's own exemption annotation, if it carries one. */
    private static Optional<String> exemptionReason(ExecutableElement declaration) {
        return declaration.getAnnotationMirrors().stream()
                .filter(mirror -> mirror.getAnnotationType().toString().equals(EXEMPTION))
                .findFirst()
                .map(ParameterCountLimit::reasonOf);
    }

    private static String reasonOf(AnnotationMirror exemption) {
        return exemption.getElementValues().entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals("reason"))
                .map(entry -> String.valueOf(entry.getValue().getValue()))
                .findFirst()
                .orElse("");
    }

    static String overLimitMessage(int count) {
        return count + " parameters; the limit is " + LIMIT + ". Group the values that travel together"
                + " into a parameter object — a record naming the concept they form — or, where they are"
                + " collaborators, make them fields of an instance whose methods take only the per-call"
                + " values; at a composition site take a facade (ADR 0010). A genuine exception is one"
                + " declaration annotated @ParameterLimitExemption(reason = \"...\").";
    }

    static String blankReasonMessage() {
        return "@ParameterLimitExemption needs a non-blank reason: the reason on the declaration is the"
                + " only record of why this signature is exempt from the " + LIMIT + "-parameter limit.";
    }

    static String unnecessaryExemptionMessage() {
        return "@ParameterLimitExemption is unnecessary: this declaration passes the " + LIMIT + "-parameter"
                + " limit without it (it is within the limit, or a record constructor or an override, which"
                + " are exempt by construction). Remove it, so the annotations stay the complete list of the"
                + " gate's real exceptions.";
    }
}
