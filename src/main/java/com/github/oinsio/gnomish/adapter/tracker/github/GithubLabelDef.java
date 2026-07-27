package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * One label definition to provision (FR5 of add-tracker-port): the resolved
 * name and color for a logical state — configured value if present, else the
 * adapter's built-in default — plus a fixed operator-hint description shown
 * on the GitHub label itself.
 *
 * <p>This record does not resolve config-vs-default itself (that combination
 * is the caller's job, e.g. wiring at task 5.15); it only carries the already
 * -resolved triple that {@link GithubLabelProvisioner} needs to create a
 * label.
 *
 * <p>The human-meaningful name plus operator-hint description are what let the
 * operator drive the factory from the tracker UI alone (UX1 of add-tracker-port):
 * the label a human clicks is self-describing, no factory-side command needed.
 *
 * <p>Implements FR5, UX1 of add-tracker-port.
 *
 * @param name the label name, e.g. {@code gnomish:ready}
 * @param color a 6-digit hex color with no leading {@code #}, e.g. {@code 2ea44f}
 * @param description the operator-hint description applied only at creation
 */
public record GithubLabelDef(String name, String color, String description) {}
