package io.snakeorcamel.agentterminal;

import java.util.Objects;
import java.util.UUID;

public final class AgentProfile {
    public enum Kind { Claude, Codex, Custom }
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public Kind kind = Kind.Custom;
    public String command = "";
    public String directory = "";
    /** Literal text on the current cursor line, not a regular expression. */
    public String readyPattern = "";

    public AgentProfile() {}
    public AgentProfile(String name, Kind kind, String command) {
        this.name = name; this.kind = kind; this.command = command;
    }
    public AgentProfile copy() {
        AgentProfile p = new AgentProfile();
        p.id = id; p.name = name; p.kind = kind; p.command = command;
        p.directory = directory; p.readyPattern = readyPattern;
        return p;
    }
    public void validate() {
        if (name.isBlank() || command.isBlank()) throw new IllegalArgumentException("A profile needs a name and a command.");
        if (hasControl(command) || hasControl(directory) || hasControl(readyPattern)) {
            throw new IllegalArgumentException("Commands, directories and readiness text must fit on one line without control characters.");
        }
        if (readyPattern.length() > 200) throw new IllegalArgumentException("Readiness text must be at most 200 characters.");
    }
    public static boolean hasControl(String value) { return value.codePoints().anyMatch(Character::isISOControl); }
    @Override public String toString() { return name; }
    @Override public boolean equals(Object o) {
        return o instanceof AgentProfile p && Objects.equals(id,p.id) && Objects.equals(name,p.name)
                && kind == p.kind && Objects.equals(command,p.command) && Objects.equals(directory,p.directory)
                && Objects.equals(readyPattern,p.readyPattern);
    }
    @Override public int hashCode() { return Objects.hash(id,name,kind,command,directory,readyPattern); }
}
