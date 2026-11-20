package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.app.ConfigurationViolations;
import com.github.oinsio.gnomish.operatorconfig.Level;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Every configuration violation of one startup, collected rather than thrown at the first (FR7,
 * NFR-O2): each becomes one line of the form {@code <what> — found in <where> — <why> — <fix>}
 * (UX1). A key reported twice from one place — the elements of one list, say — is one line.
 *
 * <p>Implements FR6, FR7, FR12, NFR-S1, NFR-S2, NFR-O2, UX1 of add-project-registry; FR17 of
 * migrate-verify-to-executor-contract.
 */
public final class ConfigViolations {

    private static final String SEPARATOR = " — ";
    private static final String VALUE_PLACEHOLDER = "<value>";

    private final ConfigLevels levels;
    private final ConfigPlaces places;
    private final Map<String, String> lines = new LinkedHashMap<>();

    public ConfigViolations(ConfigLevels levels, ConfigPlaces places) {
        this.levels = levels;
        this.places = places;
    }

    /**
     * Checks one {@code factory.*} key set in {@code source}: an unknown key, or a key whose level
     * {@code source} does not admit, is a violation.
     *
     * <p>A removed key that names its replacement ({@link RemovedKeys}) is reported as removed, with
     * the key to move its value to (FR17 of migrate-verify-to-executor-contract).
     *
     * @param key the key as the source spells it, e.g. {@code factory.egress.sandbox[0]}
     * @param location where it was found, e.g. {@code /home/op/.gnomish/factory.yaml:7}
     */
    public void key(SettingSource source, String key, String location) {
        Optional<RemovedKeys.Removed> removed = RemovedKeys.of(key);
        if (removed.isPresent()) {
            String old = removed.get().key();
            add(
                    source + old,
                    old + " is a removed key",
                    location,
                    "the factory's egress lists are one block now, factory.egress, and this list is its "
                            + removed.get().replacement() + " scope",
                    "move the list to " + removed.get().replacement());
            return;
        }
        Optional<ConfigKey> found = levels.find(key);
        if (found.isEmpty()) {
            String unknown = key.replaceAll("\\[\\d+]", "");
            add(
                    source + unknown,
                    unknown + " is not a known key",
                    location,
                    "no factory.* setting has this name (removed keys included)",
                    "remove it, or correct its spelling ('gnomish project show' lists every key)");
            return;
        }
        ConfigKey known = found.get();
        if (!source.admits(known.level())) {
            add(
                    source + known.toString(),
                    known + " is not allowed here",
                    location,
                    "it is " + article(known) + ConfigPlaces.label(known.level()) + " key, read only from "
                            + places.readFrom(known.level()),
                    places.moveTo(known.level()));
        }
    }

    /**
     * Checks the value one {@code factory.*} key was set to, where a key's values have a grammar of
     * their own: an entry of the operator egress block (FR17 of migrate-verify-to-executor-contract,
     * {@link EgressScopes}). A key with no value grammar, and an unknown key, add nothing here.
     *
     * @param key the key as the source spells it, e.g. {@code factory.egress.sandbox[0]}
     * @param value the value it was set to; a comma-separated list when the key carries no index
     * @param location where it was found
     */
    public void value(String key, @Nullable Object value, String location) {
        Optional<ConfigKey> found = levels.find(key);
        if (found.isEmpty() || value == null) {
            return;
        }
        String bound = found.get().toString();
        List<String> entries = key.endsWith("]")
                ? List.of(String.valueOf(value))
                : List.of(String.valueOf(value).split(",", -1));
        for (String entry : entries) {
            EgressScopes.refusal(bound, entry)
                    .ifPresent(refusal ->
                            add(location + bound + entry, refusal.what(), location, refusal.why(), refusal.fix()));
        }
    }

    /**
     * A {@code FACTORY_*} environment variable: never a source of configuration (FR7, NFR-S1).
     *
     * @param variable the variable's name
     * @param value its value, echoed in the equivalent line the fix suggests — or, when it holds a
     *     control character that would split or restyle the line (UX1), named by a placeholder
     */
    public void variable(String variable, String value) {
        String key = levels.propertyOf(variable);
        String shown = value.chars().anyMatch(Character::isISOControl) ? VALUE_PLACEHOLDER : value;
        String fix = levels.find(key)
                .map(known -> places.equivalent(known.level(), key, shown))
                .orElse("unset it: " + key + " is not a known key");
        add(
                "env" + variable,
                "environment variable " + variable + " is not allowed",
                "the environment",
                "factory.* settings are not read from environment variables",
                fix);
    }

    /**
     * A key outside {@code factory.*} in an operator file, which holds nothing else.
     *
     * @param holds what the file may hold, e.g. {@code factory.* keys}
     */
    public void foreign(String key, String location, String holds) {
        String unindexed = key.replaceAll("\\[\\d+]", "");
        add(
                location + unindexed,
                unindexed + " does not belong in this file",
                location,
                "the file holds only " + holds,
                "remove it");
    }

    /** A configuration file group or others may write (NFR-S2). */
    public void writable(Path file) {
        add(
                "writable" + file,
                file + " is writable by group or others",
                file.toString(),
                "a file others can write could widen the sandbox",
                "run: chmod go-w " + file);
    }

    /** A refusal whose message already names the problem and its fix — an unregistered {@code --dir}. */
    public void refusal(String message) {
        lines.putIfAbsent("refusal" + message, message);
    }

    /**
     * The collected lines, in the order found — the value both forms of the loader decide on: the
     * startup refusal and {@code doctor}'s listing (FR7 of add-doctor-command).
     */
    public ConfigurationViolations collected() {
        return new ConfigurationViolations(List.copyOf(lines.values()));
    }

    /** "an any key", "a host key". */
    private static String article(ConfigKey key) {
        return key.level() == Level.ANY ? "an " : "a ";
    }

    private void add(String identity, String what, String where, String why, String fix) {
        lines.putIfAbsent(identity, what + SEPARATOR + "found in " + where + SEPARATOR + why + SEPARATOR + fix);
    }
}
