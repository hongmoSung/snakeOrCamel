package io.snakeorcamel.agentterminal;

import com.intellij.ui.content.Content;
import com.intellij.openapi.application.ApplicationManager;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;

final class ClassicTerminalSession implements TerminalAccess.Session {
    private final ShellTerminalWidget widget;
    private final Content content;
    private final BooleanSupplier exists;
    private final Runnable focus;
    private record ProcessState(TtyConnector connector, boolean running, long sampledAt) {}
    private volatile ProcessState processState;
    ClassicTerminalSession(ShellTerminalWidget widget, Content content, BooleanSupplier exists, Runnable focus) {
        this.widget = widget; this.content = content; this.exists = exists; this.focus = focus;
    }
    @Override public Object identity() { return widget; }
    @Override public boolean exists() { return content.getManager() != null && exists.getAsBoolean(); }
    @Override public void refreshProcessState() {
        var application = ApplicationManager.getApplication();
        if (application.isDispatchThread() || application.isReadAccessAllowed()) {
            throw new IllegalStateException("Terminal process probes require a background thread without a read action.");
        }
        var connector = widget.getTtyConnector();
        processState = null;
        if (connector == null || !connector.isConnected()) return;
        try {
            boolean running = widget.hasRunningCommands();
            if (widget.getTtyConnector() == connector && connector.isConnected()) {
                processState = new ProcessState(connector,running,System.nanoTime());
            }
        } catch (RuntimeException | LinkageError ex) {
            AgentSessions.diagnostic(this, "probe", TerminalAccess.ProcessStatus.UNKNOWN, ex);
            // Unsupported connectors have unknown state, never a confirmed empty shell.
        }
    }
    @Override public TerminalAccess.Connection connection() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        var connector = widget.getTtyConnector();
        boolean connected = connector != null && connector.isConnected();
        ProcessState state = processState;
        var status = connected && state != null && state.connector == connector
                && System.nanoTime() - state.sampledAt <= TimeUnit.SECONDS.toNanos(1)
                ? (state.running ? TerminalAccess.ProcessStatus.RUNNING : TerminalAccess.ProcessStatus.IDLE)
                : TerminalAccess.ProcessStatus.UNKNOWN;
        return new TerminalAccess.Connection(connector, connected, status);
    }
    @Override public TerminalAccess.Screen screen() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        var connector = widget.getTtyConnector();
        if (connector == null || !connector.isConnected()) return TerminalAccess.Screen.unknown();
        var buffer = widget.getTerminalTextBuffer();
        if (!buffer.tryLock()) return TerminalAccess.Screen.unknown();
        try {
            var terminal = widget.getTerminal();
            int row = Math.max(0, Math.min(buffer.getHeight()-1, terminal.getCursorY()-1));
            String line = buffer.getLine(row).getText();
            int column = Math.max(0, Math.min(line.length(), terminal.getCursorX()-1));
            // Process queries may block and are forbidden on EDT in newer Classic terminals.
            ProcessState state = processState;
            boolean known = state != null && state.connector == connector
                    && System.nanoTime()-state.sampledAt <= TimeUnit.SECONDS.toNanos(1);
            boolean running = known && state.running;
            // Classic tracks shell input independently of the rendered prompt. Prompt themes,
            // right prompts, and multi-line prompts are not evidence of a typed command.
            boolean idle = known && !running && !buffer.isUsingAlternateBuffer()
                    && widget.getTypedShellCommand().isEmpty();
            int below = 0;
            for (int i = row + 1; i < buffer.getHeight(); i++) {
                if (!buffer.getLine(i).getText().replace('\u0000',' ').isBlank()) below++;
            }
            return new TerminalAccess.Screen(buffer.getScreenLines(), line, line.substring(0,column),
                    below, true, idle, running, connector);
        } catch (IllegalStateException ex) { return TerminalAccess.Screen.unknown(); }
        finally { buffer.unlock(); }
    }
    // This Classic adapter has no reliable public live working-directory accessor.
    @Override public Path directory() { return null; }
    @Override public void execute(String command) {
        if (!screen().emptyShell()) throw new IllegalStateException("The shell is not empty and ready. No command was sent.");
        send(command + "\r");
    }
    @Override public void insert(String text) {
        if (AgentProfile.hasControl(text)) throw new IllegalArgumentException("Reference contains control characters.");
        send(text);
    }
    private void send(String text) {
        var starter = widget.getTerminalStarter();
        if (starter == null || widget.getTtyConnector() == null || !widget.getTtyConnector().isConnected()) {
            throw new IllegalStateException("Terminal process is not connected.");
        }
        starter.sendString(text, false);
    }
    @Override public void focus() { focus.run(); widget.requestFocus(); }
}
