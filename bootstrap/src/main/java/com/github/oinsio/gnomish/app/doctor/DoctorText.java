package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.logtext.FailureReason;
import com.github.oinsio.gnomish.logtext.LogText;
import org.jspecify.annotations.NullMarked;

/**
 * The one way a doctor check turns a caught exception into verdict text, and the fixes several
 * checks share (NFR-O1, UX1 of add-doctor-command). An exception message may carry a tool's or the
 * tracker's text and may be absent, so no check reads one itself: {@link #words} renders an owner's
 * own refusal, {@link #fault} a failure no owner worded, and both leave through {@link
 * LogText#forConsole}, so the verdict holds console-ready text as {@link DoctorVerdict} requires.
 *
 * <p>Implements NFR-O1, UX1 of add-doctor-command.
 */
@NullMarked
final class DoctorText {

    /**
     * The fix of a result whose detail is an owner's refusal that already names its own remedy — a
     * configuration violation, a registry refusal, an operator precondition refusal, a secrets
     * refusal: the owner's words are kept as the startup path prints them (FR4), and the fix points
     * at them rather than restating them in a second spelling.
     */
    static final String AS_THE_LINE_NAMES = "do what the line names, then run gnomish doctor again";

    /** The fix of an unregistered tracker type, which the tracker wiring's refusal lists the supported ones of. */
    static final String TRACKER_TYPE_FIX =
            "set the type of the pipeline law's tracker section to one the line lists, then run gnomish doctor again";

    private DoctorText() {}

    /**
     * An owner's refusal in its own words, rendered for the console; its type when it has none.
     *
     * @param refusal the owner's exception; never null
     * @return console-ready text; never null
     */
    static String words(Throwable refusal) {
        String message = refusal.getMessage();
        return LogText.forConsole(message == null ? refusal.getClass().getName() : message);
    }

    /**
     * A failure no owner worded: its type and its words, rendered for the console.
     *
     * @param failure the exception; never null
     * @return console-ready text; never null
     */
    static String fault(Throwable failure) {
        return LogText.forConsole(FailureReason.of(failure));
    }
}
