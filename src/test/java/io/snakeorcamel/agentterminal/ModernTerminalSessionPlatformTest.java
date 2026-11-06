package io.snakeorcamel.agentterminal;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.terminal.frontend.view.TerminalView;
import com.intellij.terminal.frontend.view.TerminalViewSessionState;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.flow.StateFlow;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Exercises the Java adapter's suspend-Boolean bridge without Kotlin internal Result helpers. */
public class ModernTerminalSessionPlatformTest extends BasePlatformTestCase {
    public void testImmediateProcessResultsAndConnectionState() {
        var terminal = new View(); var session = terminal.session();
        terminal.lookup = callback -> false; refresh(session).join();
        assertEquals(TerminalAccess.ProcessStatus.IDLE, session.connection().process());
        terminal.lookup = callback -> true; refresh(session).join();
        assertEquals(TerminalAccess.ProcessStatus.RUNNING, session.connection().process());
        terminal.state = TerminalViewSessionState.NotStarted.INSTANCE;
        assertFalse(session.connection().connected());
        assertEquals(TerminalAccess.ProcessStatus.UNKNOWN, session.connection().process());
    }
    public void testSuspendedSuccessPublishesABoolean() {
        var terminal = new View(); var session = terminal.session();
        var callback = suspendNext(terminal); var probe = startRefresh(session);
        waitFor("suspended process query", () -> callback.get() != null);
        assertEquals(TerminalAccess.ProcessStatus.UNKNOWN, session.connection().process());
        callback.get().resumeWith(Boolean.TRUE); waitFor("resumed process query", probe::isDone); probe.join();
        assertEquals(TerminalAccess.ProcessStatus.RUNNING, session.connection().process());
    }
    public void testFailedResumeAndThrownQueryDiscardThePreviousIdleResult() {
        var terminal = new View(); var session = terminal.session();
        terminal.lookup = callback -> false; refresh(session).join();
        assertEquals(TerminalAccess.ProcessStatus.IDLE, session.connection().process());
        var callback = suspendNext(terminal); var probe = startRefresh(session);
        waitFor("suspended failure", () -> callback.get() != null);
        // The bridge must reject every non-Boolean completion, including Kotlin's opaque failure wrapper.
        callback.get().resumeWith(new Object()); waitFor("failed query complete", probe::isDone); probe.join();
        assertEquals(TerminalAccess.ProcessStatus.UNKNOWN, session.connection().process());
        terminal.lookup = ignored -> { throw new IllegalStateException("query failed"); };
        refresh(session).join(); assertEquals(TerminalAccess.ProcessStatus.UNKNOWN, session.connection().process());
    }
    public void testTimeoutIgnoresLateResume() {
        var terminal = new View(); var session = terminal.session();
        var callback = suspendNext(terminal); var probe = startRefresh(session);
        waitFor("suspended lookup", () -> callback.get() != null);
        waitFor("two-second query timeout", probe::isDone); probe.join();
        callback.get().resumeWith(Boolean.TRUE);
        assertEquals(TerminalAccess.ProcessStatus.UNKNOWN, session.connection().process());
    }
    public void testReplacedConnectionDiscardsSuspendedResult() {
        var terminal = new View(); var session = terminal.session();
        var callback = suspendNext(terminal); var probe = startRefresh(session);
        waitFor("old connection query", () -> callback.get() != null);
        terminal.connection = View.deferred(); callback.get().resumeWith(Boolean.FALSE);
        waitFor("old connection result", probe::isDone); probe.join();
        assertEquals(TerminalAccess.ProcessStatus.UNKNOWN, session.connection().process());
    }
    public void testLateOldProbeCannotOverwriteNewRunningResult() {
        var terminal = new View(); var session = terminal.session();
        var callback = suspendNext(terminal); var old = startRefresh(session);
        waitFor("old suspended query", () -> callback.get() != null);
        terminal.lookup = ignored -> true; refresh(session).join();
        callback.get().resumeWith(Boolean.FALSE); waitFor("old completion", old::isDone); old.join();
        assertEquals(TerminalAccess.ProcessStatus.RUNNING, session.connection().process());
    }
    public void testNoShellOutputOrIntegrationIsReadAndExistingTabCannotExecute() {
        var terminal = new View(); var session = terminal.session();
        assertEquals(TerminalAccess.Screen.unknown(), session.screen()); assertFalse(session.emptyShell());
        assertThrows(IllegalStateException.class, () -> session.execute("codex"));
        session.insert(" @file#L1 "); assertEquals(List.of(" @file#L1 "), terminal.input);
        assertThrows(IllegalArgumentException.class, () -> session.insert("reference\n"));
        assertEquals(Path.of("/tmp"), session.directory());
    }
    private static AtomicReference<Continuation<? super Boolean>> suspendNext(View terminal) {
        var callback = new AtomicReference<Continuation<? super Boolean>>();
        terminal.lookup = next -> { callback.set(next); return new Object(); };
        return callback;
    }
    private static CompletableFuture<Void> startRefresh(ModernTerminalSession session) {
        var done = new CompletableFuture<Void>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { session.refreshProcessState(); done.complete(null); }
            catch (Throwable ex) { done.completeExceptionally(ex); }
        });
        return done;
    }
    private static CompletableFuture<Void> refresh(ModernTerminalSession session) {
        var probe = startRefresh(session); waitFor("process query", probe::isDone); return probe;
    }
    private static void waitFor(String label, java.util.function.BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching(label, condition, 5);
    }
    private static final class View {
        volatile Deferred<?> connection = deferred();
        volatile TerminalViewSessionState state = TerminalViewSessionState.Running.INSTANCE;
        volatile Function<Continuation<? super Boolean>, Object> lookup = callback -> true;
        final List<String> input = new ArrayList<>();
        ModernTerminalSession session() {
            var view = proxy(TerminalView.class, (method, args) -> switch (method) {
                case "getStartupOptionsDeferred" -> connection;
                case "getSessionState" -> proxy(StateFlow.class, (name, ignored) -> {
                    if (name.equals("getValue")) return state;
                    throw new AssertionError(name);
                });
                case "getWorkingDirectoryFlow" -> proxy(StateFlow.class, (name, ignored) -> {
                    if (name.equals("getValue")) return Path.of("/tmp");
                    throw new AssertionError(name);
                });
                case "hasChildProcesses" -> {
                    var app = ApplicationManager.getApplication();
                    assertFalse(app.isDispatchThread()); assertFalse(app.isReadAccessAllowed());
                    yield lookup.apply(callback(args[0]));
                }
                case "sendText" -> { input.add((String) args[0]); yield null; }
                default -> throw new AssertionError("Unexpected Terminal API: " + method);
            });
            var content = proxy(Content.class, (method, args) -> { throw new AssertionError(method); });
            return new ModernTerminalSession(view, content, () -> true, () -> {});
        }
        static Deferred<?> deferred() {
            return proxy(Deferred.class, (method, args) -> { throw new AssertionError("Must not await connection identity: " + method); });
        }
        @SuppressWarnings("unchecked")
        private static Continuation<? super Boolean> callback(Object value) { return (Continuation<? super Boolean>) value; }
    }
    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> type.getSimpleName();
            default -> call.invoke(method.getName(), args);
        }));
    }
}
