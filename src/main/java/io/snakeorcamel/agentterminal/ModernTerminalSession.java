package io.snakeorcamel.agentterminal;

import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;
import com.intellij.terminal.frontend.view.TerminalView;
import com.intellij.terminal.frontend.view.TerminalViewSessionState;
import org.jetbrains.annotations.NotNull;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Public, experimental Reworked APIs. Launch only in a newly created tab; never inspect shell output. */
final class ModernTerminalSession implements TerminalAccess.Session {
    private final TerminalView view;
    private final Content content;
    private final BooleanSupplier exists;
    private final Runnable focus;
    ModernTerminalSession(TerminalView view, Content content, BooleanSupplier exists, Runnable focus) {
        this.view = view; this.content = content;
        this.exists = exists; this.focus = focus;
    }
    private static ModernTerminalSession create(Project project, Path directory, String name, Predicate<Content> exists, Consumer<Content> focus) {
        var tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
                .workingDirectory(directory.toString()).tabName(name).requestFocus(true)
                .deferSessionStartUntilUiShown(false).createTab();
        Content content = tab.getContent();
        return new ModernTerminalSession(tab.getView(), content, () -> exists.test(content), () -> focus.accept(content));
    }
    static TerminalAccess.Session createForStartup(Project project, Path directory, String name, String command,
                                                  Predicate<Content> exists, Consumer<Content> focus) {
        var session = create(project, directory, name, exists, focus);
        // Only a newly created tab may bypass empty-shell detection. The public input API
        // buffers until the shell is connected, in order, before any user input can arrive.
        // Powerlevel10k disables JetBrains shell integration, so waiting for it never finishes.
        session.view.createSendTextBuilder().shouldExecute().send(command);
        return session;
    }
    @Override public Object identity() { return view; }
    @Override public boolean exists() { return content.getManager() != null && exists.getAsBoolean(); }
    private record Probe(Object connection, TerminalAccess.ProcessStatus status, long sampledAt, long sequence) {}
    private final java.util.concurrent.atomic.AtomicLong probeSequence = new java.util.concurrent.atomic.AtomicLong();
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
        var status = connected && current != null && current.sequence == probeSequence.get() && current.connection == identity
                && System.nanoTime() - current.sampledAt <= java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
                ? current.status : TerminalAccess.ProcessStatus.UNKNOWN;
        return new TerminalAccess.Connection(identity, connected, status);
    }
    @Override public void refreshProcessState() {
        var application = com.intellij.openapi.application.ApplicationManager.getApplication();
        if (application.isDispatchThread() || application.isReadAccessAllowed()) {
            throw new IllegalStateException("Terminal process probes require a background thread without a read action.");
        }
        long sequence = probeSequence.incrementAndGet();
        probe = null;
        try {
            Object identity = connectionIdentity();
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Continuation<Boolean> callback = new Continuation<>() {
                @Override public @NotNull CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
                @Override public void resumeWith(@NotNull Object answer) {
                    // A successful Boolean Result is unboxed. Any other value is a failed
                    // query: do not inspect Kotlin's internal Result.Failure representation.
                    if (answer instanceof Boolean running) result.complete(running);
                    else result.completeExceptionally(new IllegalStateException("Terminal process query failed."));
                }
            };
            Object immediate = view.hasChildProcesses(callback);
            boolean running = immediate instanceof Boolean value ? value
                    : result.get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (probeSequence.get() == sequence && connectionIdentity() == identity) {
                probe = new Probe(identity, running ? TerminalAccess.ProcessStatus.RUNNING : TerminalAccess.ProcessStatus.IDLE,
                        System.nanoTime(), sequence);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            AgentSessions.diagnostic(this, "probe", TerminalAccess.ProcessStatus.UNKNOWN, ex);
        } catch (Exception | LinkageError ex) {
            AgentSessions.diagnostic(this, "probe", TerminalAccess.ProcessStatus.UNKNOWN, ex);
        }
    }
    @Override public TerminalAccess.Screen screen() {
        // Reworked queues startup atomically at creation. Existing/idle tabs are
        // never launched into, so no output or shell-integration APIs are needed.
        return TerminalAccess.Screen.unknown();
    }
    @Override public Path directory() {
        return view.getWorkingDirectoryFlow().getValue();
    }
    @Override public void execute(String command) {
        throw new IllegalStateException("Reworked commands may only be queued when creating a dedicated tab.");
    }
    @Override public void insert(String text) {
        if (AgentProfile.hasControl(text)) throw new IllegalArgumentException("Reference contains control characters.");
        view.sendText(text);
    }
    @Override public void focus() { focus.run(); }
}
