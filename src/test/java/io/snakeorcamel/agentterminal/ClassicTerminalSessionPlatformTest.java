package io.snakeorcamel.agentterminal;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.ContentFactory;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the real Classic adapter against a widget enforcing the newer IDE's process-thread contract. */
public class ClassicTerminalSessionPlatformTest extends BasePlatformTestCase {
    public void testScreenCaptureOnEdtNeverQueriesProcesses() {
        var widget = widget();
        var session = session(widget);

        assertFalse(session.screen().emptyShell());
        assertFalse(session.screen().commandRunning());
        assertEquals(0,widget.probes.get());

        refresh(session);

        assertTrue(session.screen().emptyShell());
        assertFalse(session.screen().commandRunning());
        assertEquals(1,widget.probes.get());
    }

    public void testRunningProcessIsReadInBackgroundAndPublishedToEdt() {
        var widget = widget(); widget.running = true;
        var session = session(widget);
        refresh(session);

        assertTrue(session.screen().commandRunning());
        assertFalse(session.screen().emptyShell());
        assertEquals(1,widget.probes.get());
    }

    public void testReplacedConnectorCannotReuseThePreviousIdleResult() {
        var widget = widget();
        var session = session(widget);
        refresh(session); assertTrue(session.screen().emptyShell());

        widget.connector = new ConnectedTty();

        assertFalse(session.screen().emptyShell());
        assertFalse(session.screen().commandRunning());
        widget.running = true; refresh(session);
        assertTrue(session.screen().commandRunning());
    }

    public void testFailedProcessProbeIsUnknownRatherThanAnEmptyShell() {
        var widget = widget();
        var session = session(widget);
        refresh(session); assertTrue(session.screen().emptyShell());

        widget.unsupported = true; refresh(session);

        assertFalse(session.screen().emptyShell());
        assertFalse(session.screen().commandRunning());
    }

    private ProbeWidget widget() {
        var widget = new ProbeWidget(getProject(),getTestRootDisposable());
        widget.getTerminal().writeCharacters("$ ");
        return widget;
    }

    private ClassicTerminalSession session(ProbeWidget widget) {
        var content = ContentFactory.getInstance().createContent(widget,"Classic test",false);
        return new ClassicTerminalSession(widget,content,() -> true,() -> {});
    }

    private void refresh(ClassicTerminalSession session) {
        var completed = new CompletableFuture<Void>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { session.refreshProcessState(); completed.complete(null); }
            catch (Throwable ex) { completed.completeExceptionally(ex); }
        });
        PlatformTestUtil.waitWithEventsDispatching("background process probe",completed::isDone,5);
        completed.join();
    }

    private static final class ProbeWidget extends ShellTerminalWidget {
        volatile TtyConnector connector = new ConnectedTty();
        volatile boolean running;
        volatile boolean unsupported;
        final AtomicInteger probes = new AtomicInteger();
        ProbeWidget(Project project, Disposable parent) { super(project,new JBTerminalSystemSettingsProvider(),parent); }
        @Override public TtyConnector getTtyConnector() { return connector; }
        @Override public String getTypedShellCommand() { return ""; }
        @Override public boolean hasRunningCommands() {
            var application = ApplicationManager.getApplication();
            assertFalse("Process queries must never run on EDT",application.isDispatchThread());
            assertFalse("Process queries must not hold read access",application.isReadAccessAllowed());
            probes.incrementAndGet();
            if (unsupported) throw new IllegalStateException("Unsupported connector");
            return running;
        }
    }

    private static final class ConnectedTty implements TtyConnector {
        @Override public int read(char[] buffer,int offset,int length) { return -1; }
        @Override public void write(byte[] bytes) {}
        @Override public void write(String text) {}
        @Override public boolean isConnected() { return true; }
        @Override public int waitFor() { return 0; }
        @Override public boolean ready() { return false; }
        @Override public String getName() { return "Classic test"; }
        @Override public void close() {}
    }
}
