package io.snakeorcamel.agentterminal;

import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.terminal.frontend.view.TerminalView;
import com.intellij.terminal.frontend.view.TerminalViewSessionState;
import org.jetbrains.plugins.terminal.view.*;
import org.jetbrains.plugins.terminal.view.shellIntegration.*;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import java.nio.file.Path;
import java.util.function.*;

/** Public Terminal APIs for the supported IntelliJ platform. */
final class ModernTerminalSession implements TerminalAccess.Session {
    private final TerminalView view;
    private final Content content;
    private final BooleanSupplier exists;
    private final Runnable focus;
    private ModernTerminalSession(TerminalToolWindowTab tab, BooleanSupplier exists, Runnable focus) {
        view = tab.getView(); content = tab.getContent();
        this.exists = exists; this.focus = focus;
    }
    static TerminalAccess.Session find(Project project, Content content, BooleanSupplier exists, Runnable focus) {
        for (var tab : TerminalToolWindowTabsManager.getInstance(project).getTabs()) {
            if (tab.getContent() == content) return new ModernTerminalSession(tab, exists, focus);
        }
        return null;
    }
    static TerminalAccess.Session create(Project project, Path directory, String name, Predicate<Content> exists, Consumer<Content> focus) {
        var tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
                .workingDirectory(directory.toString()).tabName(name).requestFocus(true)
                .deferSessionStartUntilUiShown(false).createTab();
        Content content = tab.getContent();
        return new ModernTerminalSession(tab, () -> exists.test(content), () -> focus.accept(content));
    }
    @Override public Object identity() { return view; }
    @Override public boolean exists() { return content.getManager() != null && exists.getAsBoolean(); }
    @Override public boolean starting() {
        return view.getSessionState().getValue() instanceof TerminalViewSessionState.NotStarted;
    }
    private record Probe(Object connection, TerminalAccess.ProcessStatus status, long sampledAt) {}
    private volatile Probe probe;
    private Object connectionIdentity() {
        // IDEA 2026.2.4 keeps this public Deferred for the lifetime of the view's shell session.
        // Compare the handle without awaiting startup or accessing the internal session API.
        return view.getStartupOptionsDeferred();
    }
    @Override public TerminalAccess.Connection connection() {
        var state = view.getSessionState().getValue();
        Object identity = connectionIdentity();
        boolean connected = state instanceof TerminalViewSessionState.Running;
        Probe current = probe;
        var status = connected && current != null && current.connection == identity
                && System.nanoTime() - current.sampledAt <= java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
                ? current.status : TerminalAccess.ProcessStatus.UNKNOWN;
        return new TerminalAccess.Connection(identity, connected, status);
    }
    @Override public void refreshProcessState() {
        var application = com.intellij.openapi.application.ApplicationManager.getApplication();
        if (application.isDispatchThread() || application.isReadAccessAllowed()) {
            throw new IllegalStateException("Terminal process probes require a background thread without a read action.");
        }
        probe = null;
        try {
            Object identity = connectionIdentity();
            var result = new java.util.concurrent.CompletableFuture<Object>();
            Continuation<Boolean> callback = new Continuation<>() {
                @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
                @Override public void resumeWith(Object answer) { result.complete(answer); }
            };
            Object immediate = view.hasChildProcesses(callback);
            Object answer = immediate instanceof Boolean ? immediate
                    : result.get(2, java.util.concurrent.TimeUnit.SECONDS);
            ResultKt.throwOnFailure(answer);
            if (connectionIdentity() == identity && answer instanceof Boolean running) {
                probe = new Probe(identity, running ? TerminalAccess.ProcessStatus.RUNNING : TerminalAccess.ProcessStatus.IDLE,
                        System.nanoTime());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            AgentSessions.diagnostic(this, "probe", TerminalAccess.ProcessStatus.UNKNOWN, ex);
        } catch (Exception | LinkageError ex) {
            AgentSessions.diagnostic(this, "probe", TerminalAccess.ProcessStatus.UNKNOWN, ex);
        }
    }
    @Override public TerminalAccess.Screen screen() {
        if (!(view.getSessionState().getValue() instanceof TerminalViewSessionState.Running)) return TerminalAccess.Screen.unknown();
        var models = view.getOutputModels();
        var active = models.getActive().getValue();
        var snapshot = active.takeSnapshot();
        var cursor = snapshot.getCursorOffset();
        var cursorRow = snapshot.getLineByOffset(cursor);
        var rowStart = snapshot.getStartOfLine(cursorRow);
        var rowEnd = snapshot.getEndOfLine(cursorRow, false);
        String line = snapshot.getText(rowStart, rowEnd).toString();
        String prefix = snapshot.getText(rowStart, cursor).toString();
        long first = snapshot.getFirstLineIndex().toAbsolute();
        long last = snapshot.getLastLineIndex().toAbsolute();
        var grid = view.getGridSize();
        if (grid == null) return TerminalAccess.Screen.unknown();
        var startLine = TerminalLineIndex.of(Math.max(first, last - grid.getRows() + 1));
        var start = snapshot.getStartOfLine(startLine);
        var end = snapshot.getEndOffset();
        String screen = snapshot.getText(start, end).toString();
        int below = (int) snapshot.getText(rowEnd, end).toString().lines().filter(s -> !s.isBlank()).count();
        boolean idle = emptyShell(snapshot, end, active == models.getRegular());
        var integration = integration();
        boolean running = integration != null && integration.getOutputStatus().getValue() instanceof TerminalOutputStatus.ExecutingCommand;
        return new TerminalAccess.Screen(screen, line, prefix, below, true, idle, running,
                connectionIdentity());
    }
    private TerminalShellIntegration integration() {
        var deferred = view.getShellIntegrationDeferred();
        if (!deferred.isCompleted() || deferred.isCancelled()) return null;
        return deferred.getCompleted();
    }
    private boolean emptyShell(TerminalOutputModelSnapshot snapshot, TerminalOffset end, boolean regular) {
        if (!regular) return false;
        var integration = integration();
        if (integration == null || !(integration.getOutputStatus().getValue() instanceof TerminalOutputStatus.TypingCommand)) return false;
        var block = integration.getBlocksModel().getActiveBlock();
        if (!(block instanceof TerminalCommandBlock command)) return false;
        var commandStart = command.getCommandStartOffset();
        if (commandStart == null) return false;
        long from = commandStart.toAbsolute();
        if (from < snapshot.getStartOffset().toAbsolute() || from > end.toAbsolute()) return false;
        return snapshot.getText(commandStart, end).toString().isBlank();
    }
    @Override public Path directory() {
        return view.getWorkingDirectoryFlow().getValue();
    }
    @Override public void execute(String command) {
        if (!screen().emptyShell()) throw new IllegalStateException("The shell is not empty and ready. No command was sent.");
        view.createSendTextBuilder().shouldExecute().send(command);
    }
    @Override public void insert(String text) {
        if (AgentProfile.hasControl(text)) throw new IllegalArgumentException("Reference contains control characters.");
        view.sendText(text);
    }
    @Override public void focus() { focus.run(); }
}
