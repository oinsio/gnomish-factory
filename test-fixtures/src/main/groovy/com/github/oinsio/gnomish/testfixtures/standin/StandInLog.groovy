package com.github.oinsio.gnomish.testfixtures.standin

import java.nio.file.Files
import java.nio.file.Path

/**
 * What a per-run stand-in link recorded (ADR 0015): the blocks its {@code record} rows appended to
 * {@code <link>.log}, read back the way {@code stand-in.sh} wrote them — one {@code key=value} line
 * per value, a backslash written as two and a line break as {@code \\n}, each block closed by
 * {@link #END_OF_BLOCK}.
 */
final class StandInLog {

    /** The line that closes every block a {@code record} row appends. */
    static final String END_OF_BLOCK = '--'

    private StandInLog() {
    }

    /** The blocks {@code link} recorded, in order, each as its lines keyed by name; none yet is empty. */
    static List<Map<String, String>> blocks(Path link) {
        Path log = StandIn.log(link)
        if (!Files.exists(log)) {
            return []
        }
        List<Map<String, String>> blocks = []
        Map<String, String> current = [:]
        log.toFile().readLines().each { String line ->
            if (line == END_OF_BLOCK) {
                blocks << current
                current = [:]
            } else {
                int at = line.indexOf('=')
                current[line.substring(0, at)] = unescape(line.substring(at + 1))
            }
        }
        blocks
    }

    /** A recorded value as it was: the script writes a backslash as two and a line break as {@code \n}. */
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length())
        int i = 0
        while (i <value.length()) {
            char c = value.charAt(i)
            if (c == '\\' as char && i + 1 <value.length()) {
                out.append(value.charAt(i + 1) == 'n' as char ? '\n' : value.charAt(i + 1))
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        out.toString()
    }

    /** The arguments of one recorded block, each as it was passed — an argument may hold spaces. */
    static List<String> argv(Map<String, String> block) {
        List<String> arguments = []
        for (int n = 1; block.containsKey("arg.${n}".toString()); n++) {
            arguments << block["arg.${n}".toString()]
        }
        arguments
    }
}
