package io.snakeorcamel.agentterminal;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative current-screen recognizers. A process or elapsed delay is never evidence of readiness. */
public final class ReadyDetector {
    public enum State { EMPTY_SHELL, STARTING, CONFIRMATION, READY, UNKNOWN }
    public record Result(State state, AgentProfile.Kind kind) {}
    private static final Pattern CONFIRM = Pattern.compile(
            "(?i)(do you trust|trust this (folder|workspace)|yes,? (proceed|allow)|allow (once|always)|"
                    + "approve this|permission required|sign in to|log in to|not logged in|run /login|login with|select.*account|"
                    + "would you like to|press enter to|accept.*terms|enter.*code|continue with|confirm.*(access|permission))");
    private static final Pattern CODEX_MODEL_FOOTER = Pattern.compile("(?im)^\\s*(gpt-|codex-|o[134][ -])[^\\r\\n]*·");
    private static final Pattern CODEX_AGENT_SHORTCUTS = Pattern.compile("(?im)^[^\\r\\n]*\\bfor agents\\s*·\\s*\\? for shortcuts\\b");
    public Result detect(TerminalAccess.Screen screen, AgentProfile profile) {
        if (!screen.connected()) return new Result(State.STARTING, null);
        String text = screen.text().replace('\u0000',' ');
        String lower = text.toLowerCase(Locale.ROOT);
        if (CONFIRM.matcher(text).find()) return new Result(State.CONFIRMATION, null);
        if (Pattern.compile("(?im)^\\s*loading\\s*$").matcher(text).find()) return new Result(State.STARTING,null);
        if (screen.emptyShell()) return new Result(State.EMPTY_SHELL, null);
        if (!screen.commandRunning()) return new Result(State.UNKNOWN, null);
        String cursor = screen.cursorLine().stripLeading();
        String prefix = screen.beforeCursor().stripLeading();
        boolean nearBottom = screen.linesBelowCursor() <= 8;
        boolean busy = lower.contains("esc to interrupt") || lower.contains("esc to cancel") || lower.contains("working (");
        if (profile != null && profile.kind == AgentProfile.Kind.Custom) {
            boolean ready = !busy && nearBottom && !profile.readyPattern.isBlank()
                    && cursor.startsWith(profile.readyPattern) && prefix.startsWith(profile.readyPattern);
            return new Result(ready ? State.READY : State.UNKNOWN,ready ? AgentProfile.Kind.Custom : null);
        }
        if (!busy && nearBottom && cursor.startsWith("❯") && prefix.startsWith("❯")
                && (lower.contains("claude code") || lower.contains("? for shortcuts"))) {
            return new Result(State.READY, AgentProfile.Kind.Claude);
        }
        boolean modelFooter = CODEX_MODEL_FOOTER.matcher(text).find();
        boolean codexFooter = lower.contains("context left") || lower.contains("? for shortcuts") || modelFooter;
        // Codex's compact composer keeps its model and agent-shortcut footer after the banner
        // scrolls off screen. It also stays identifiable when a draft replaces the placeholder.
        boolean compactCodex = modelFooter && CODEX_AGENT_SHORTCUTS.matcher(text).find();
        if (!busy && nearBottom && codexFooter && cursor.startsWith("›") && prefix.startsWith("›")
                && (lower.contains("openai codex") || (lower.contains("context left") && lower.contains("? for shortcuts")) || compactCodex)) {
            return new Result(State.READY, AgentProfile.Kind.Codex);
        }
        return new Result(State.UNKNOWN, null);
    }
}
