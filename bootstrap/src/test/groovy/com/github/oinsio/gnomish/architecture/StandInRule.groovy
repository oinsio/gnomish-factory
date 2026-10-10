package com.github.oinsio.gnomish.architecture

import java.util.regex.Pattern

/**
 * What writing a stand-in binary looks like in a test source (FR24 of
 * supervise-daemon-loops-and-embed-dashboard, design D23, ADR 0015): the shapes
 * {@link StandInOwnerSpec} scans for, held apart from the gate that owns the trees and the
 * exemptions, so the detector is driven over seeded sources too.
 *
 * <p>Three shapes, each enough on its own:
 *
 * <ul>
 *   <li>an executable bit set from Groovy or Java — {@code executable = true},
 *       {@code setExecutable(};
 *   <li>shebang text — {@code #!/} in any literal: a script {@link ProcessBuilder} can run directly
 *       needs one (an executable file without it fails with {@code ENOEXEC}), so this is the shape
 *       every generated stand-in had, whatever made it executable;
 *   <li>a {@code chmod} granting execute, spelled as command text — a script made executable
 *       through a shell, inside a container or out of it.
 * </ul>
 *
 * <p>Permission-mode calls ({@code PosixFilePermissions.fromString}, {@code OWNER_EXECUTE}) are
 * not a shape: specs use them to lock and unlock directories, where the execute bit is the search
 * bit and nothing is run, and a file made executable that way still needs the shebang the second
 * shape catches before anything can run it.
 */
final class StandInRule {

    /** The shapes, by name, each matched against a source with its comments stripped. */
    static final Map<String, Pattern> SHAPES = [
        'executable bit': ~/\.executable\s*=\s*true\b|\bsetExecutable\s*\(/,
        'shebang': ~/#!\//,
        'chmod granting execute': ~/\bchmod\s+(?:-\w+\s+)*[ugoa]*\+[rwX]*x/,
    ].asImmutable()

    private StandInRule() {
    }

    /** The names of the shapes {@code code} contains, in {@link #SHAPES} order; empty when none. */
    static List<String> shapesIn(String code) {
        SHAPES.findAll { name, pattern ->
            pattern.matcher(code).find()
        }.keySet().toList()
    }
}
