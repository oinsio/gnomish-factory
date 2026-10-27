package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.doctor.json.DoctorCheckDto;
import com.github.oinsio.gnomish.doctor.json.DoctorReportJsonMapper;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

/**
 * The results of one {@code gnomish doctor} run and their two renderings (FR5, FR6, UX1, UX2 of
 * add-doctor-command; design D5). {@link #text()} is the operator's checklist: one line per result,
 * top to bottom in report order — status, check name, detail and, where there is one, the fix on
 * the same line — then a one-line summary. {@link #json()} is the same results as one document for
 * a parser, each carrying its check's stable id. {@link #failed()} decides the exit code.
 *
 * <p>Both renderings carry exactly the verdicts' own text: a capture left its carrier through the
 * console exit inside the check (NFR-O1), and no verdict holds a secret value (NFR-S1), so neither
 * rendering can show one. The text still goes out through {@link ConsoleIO#print}, the human path,
 * and the document through {@link ConsoleIO#printMachine}; the caller chooses which.
 *
 * <p>Implements FR5, FR6, NFR-O1, NFR-S1, UX1, UX2 of add-doctor-command.
 */
@NullMarked
final class DoctorReport {

    private static final DoctorReportJsonMapper JSON = new DoctorReportJsonMapper();
    private static final Pattern LINE_BREAK = Pattern.compile("\\s*\\R\\s*");

    private final List<DoctorVerdict> verdicts;

    /** @param verdicts every result of the run, in report order */
    DoctorReport(List<DoctorVerdict> verdicts) {
        this.verdicts = List.copyOf(verdicts);
    }

    /** How many results are {@code fail}: the run exits 1 when this is above zero (FR6). */
    long failed() {
        return count(DoctorStatus.FAIL);
    }

    /** One line per result, then the summary line; every line ends with {@link ConsoleIO#LINE_END}. */
    String text() {
        StringBuilder text = new StringBuilder();
        for (DoctorVerdict verdict : verdicts) {
            text.append(line(verdict)).append(ConsoleIO.LINE_END);
        }
        return text.append(summary()).append(ConsoleIO.LINE_END).toString();
    }

    /** The {@code --json} document, ending with {@link ConsoleIO#LINE_END}. */
    String json() {
        List<DoctorCheckDto> checks = verdicts.stream()
                .map(v -> new DoctorCheckDto(v.check().id(), v.status().token(), v.detail(), v.fix()))
                .toList();
        return JSON.serialize(checks) + ConsoleIO.LINE_END;
    }

    /**
     * {@code <status>  <name>: <detail>}, and {@code ; fix: <fix>} when the result has a fix. A
     * detail an owner worded over several lines is joined into one, so a result stays one line.
     */
    private static String line(DoctorVerdict verdict) {
        String head =
                verdict.status().token() + "  " + verdict.check().displayName() + ": " + oneLine(verdict.detail());
        return verdict.fix() == null ? head : head + "; fix: " + oneLine(verdict.fix());
    }

    private static String oneLine(String text) {
        return LINE_BREAK.matcher(text.strip()).replaceAll(" ");
    }

    /** The counts per status, and what to do next. */
    private String summary() {
        String counts = List.of(DoctorStatus.values()).stream()
                .map(status -> count(status) + " " + status.token())
                .collect(Collectors.joining(", "));
        return "summary: " + counts
                + (failed() == 0 ? "; no check failed" : "; fix each fail line, then run gnomish doctor again");
    }

    private long count(DoctorStatus status) {
        return verdicts.stream().filter(v -> v.status() == status).count();
    }
}
