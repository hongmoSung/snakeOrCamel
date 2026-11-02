package io.snakeorcamel.agentterminal;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.util.Disposer;
import java.nio.file.*;
import java.util.*;

public class AgentPlatformTest extends BasePlatformTestCase {
    @Override protected void tearDown() throws Exception {
        try {
            getProject().getService(AgentSessions.class).cancelAll();
            AgentSettings.getInstance().getState().enabled = false;
            AgentKeymap.getInstance().reconcile();
        } finally { super.tearDown(); }
    }
    public void testDefaultsAreOffAndProjectProfilesDoNotEnableIntegration() {
        assertFalse(new AgentSettings().isEnabled());
        assertEquals(2, new AgentProfiles().profiles().size());
        assertNull(new AgentProfiles().defaultProfile());
    }
    public void testSelectionEndingAtNextLineStartExcludesThatLine() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "one\ntwo\nthree\n");
        var editor = myFixture.getEditor();
        editor.getSelectionModel().setSelection(0,8);
        // Lightweight fixture files aren't on disk: test line semantics directly with the document.
        var document = editor.getDocument();
        assertEquals(2, document.getLineNumber(editor.getSelectionModel().getSelectionEnd()-1)+1);
        try (var marker = new Marker(document.createRangeMarker(0,8))) {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0,"new\n"));
            assertEquals(3, document.getLineNumber(marker.range.getEndOffset()-1)+1);
        }
    }
    public void testNonDiskEditorsAreRejected() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "text");
        assertThrows(IllegalArgumentException.class, () -> new EditorReference(myFixture.getEditor()));
    }
    public void testRealEditorReferenceTracksSavedSelectionsAndNextLineBoundary() throws Exception {
        openDiskFile("one\ntwo\nthree\n");
        var editor = myFixture.getEditor(); editor.getSelectionModel().setSelection(0,8);
        try (var reference = new EditorReference(editor)) {
            assertTrue(reference.format(null,AgentProfile.Kind.Codex).endsWith("#L1-L2"));
            WriteCommandAction.runWriteCommandAction(getProject(),() -> editor.getDocument().insertString(0,"new\n"));
            reference.save();
            assertTrue(reference.format(null,AgentProfile.Kind.Codex).endsWith("#L2-L3"));
        }
    }
    public void testReferenceUsesFirstTabAndIsInsertedOnlyOnceWithoutEnter() throws Exception {
        var terminal = setupTerminal(); openDiskFile("first\nsecond\nthird\n");
        var editor = myFixture.getEditor(); editor.getSelectionModel().setSelection(0,13);
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(editor); sessions.insert(editor);
        PlatformTestUtil.waitWithEventsDispatching("reference inserted",() -> !terminal.first.input.isEmpty(),5);
        assertEquals(1,terminal.first.input.size()); assertTrue(terminal.first.input.getFirst().contains("#L1-L2"));
        assertFalse(terminal.first.input.getFirst().contains("\n")); assertFalse(terminal.first.input.getFirst().contains("\r"));
        assertTrue(terminal.second.input.isEmpty()); assertEquals(0,terminal.created); assertEquals(0,terminal.first.executions);
    }
    public void testTabMoveAndDisablingPreventLateInsertion() throws Exception {
        var terminal = setupTerminal(); openDiskFile("text");
        terminal.first.ready = false;
        var sessions = getProject().getService(AgentSessions.class); sessions.insert(myFixture.getEditor());
        terminal.move(); terminal.first.ready = true;
        AgentSettings.getInstance().getState().enabled = false;
        AgentKeymap.getInstance().reconcile();
        assertTrue(terminal.first.input.isEmpty()); assertTrue(terminal.second.input.isEmpty());
        assertEquals(0,terminal.first.executions);
    }
    public void testCompactCodexInExistingFirstTabReceivesReferenceWithoutRelaunchOrEnter() throws Exception {
        var terminal = setupTerminal(); openDiskFile("first\nsecond\nthird\n");
        terminal.first.compact = true;
        myFixture.getEditor().getSelectionModel().setSelection(6,13);
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor()); sessions.insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("compact Codex receives reference",() -> !terminal.first.input.isEmpty(),5);
        assertEquals(1,terminal.first.input.size());
        assertTrue(terminal.first.input.getFirst().strip().endsWith("#L2"));
        assertFalse(terminal.first.input.getFirst().contains("\n"));
        assertFalse(terminal.first.input.getFirst().contains("\r"));
        assertEquals(0,terminal.first.executions);
        assertEquals(0,terminal.created);
        assertTrue(terminal.second.input.isEmpty());
    }
    public void testRepeatedRunDoesNotExecuteTwiceWhileAgentIsStarting() {
        var terminal = setupTerminal(); terminal.first.ready = false;
        var profile = new AgentProfile("Custom",AgentProfile.Kind.Custom,"custom");
        profile.directory = System.getProperty("java.io.tmpdir");
        var sessions = getProject().getService(AgentSessions.class); sessions.run(profile); sessions.run(profile);
        PlatformTestUtil.waitWithEventsDispatching("one launch",() -> terminal.first.executions > 0,5);
        sessions.run(profile);
        assertEquals(1,terminal.created); assertEquals(1,terminal.first.executions);
    }
    public void testExistingEmptyShellLaunchesDefaultAfterBackgroundProbe() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.first.shell = true; terminal.first.ready = false;
        var profile = new AgentProfile("Codex",AgentProfile.Kind.Codex,"codex");
        var profiles = AgentProfiles.getInstance(getProject()).getState();
        profiles.profiles = new ArrayList<>(List.of(profile)); profiles.defaultId = profile.id;

        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());

        PlatformTestUtil.waitWithEventsDispatching("default launched in existing shell",() -> terminal.first.executions == 1,5);
        assertEquals(0,terminal.created);
        assertTrue(terminal.first.probes.get() > 0);
        terminal.first.ready = true;
        terminal.first.rejectScreen = true;
        assertTrue(terminal.first.input.isEmpty());
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor());
        assertTrue("Startup delay must preserve the original request", terminal.first.input.isEmpty());
        PlatformTestUtil.waitWithEventsDispatching("original reference inserted automatically", () -> terminal.first.sends == 1, 5);
        assertEquals(1, terminal.first.executions);
        assertFalse(terminal.first.input.getFirst().contains("\r"));
        assertFalse(terminal.first.input.getFirst().contains("\n"));
    }

    public void testDisablingDuringAProcessProbeDiscardsLateResultsAfterReenable() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        var blocked = new java.util.concurrent.CountDownLatch(1);
        terminal.first.blockedProbe = blocked;
        var sessions = getProject().getService(AgentSessions.class);
        try {
            sessions.insert(myFixture.getEditor());
            PlatformTestUtil.waitWithEventsDispatching("probe started without blocking EDT",() -> terminal.first.probes.get() > 0,5);
            assertTrue(terminal.first.input.isEmpty());
            AgentSettings.getInstance().getState().enabled = false;
            AgentKeymap.getInstance().reconcile();
            terminal.first.blockedProbe = null;
            AgentSettings.getInstance().getState().enabled = true;
            sessions.insert(myFixture.getEditor());
            blocked.countDown();
            PlatformTestUtil.waitWithEventsDispatching("only the new request inserts",() -> !terminal.first.input.isEmpty(),5);
            assertEquals(1,terminal.first.input.size());
            assertTrue(terminal.first.probes.get() >= 2);
            assertEquals(0,terminal.first.executions);
        } finally { blocked.countDown(); }
    }
    public void testBusyOrApprovalScreenDoesNotGateExplicitInsertion() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.first.ready = false;
        terminal.first.rejectScreen = true;
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("insert without reading screen", () -> terminal.first.sends == 1, 5);
        assertEquals(0, terminal.first.executions);
        assertEquals(1, terminal.first.focuses);
    }
    public void testUnknownConnectedProcessInsertsWithoutLaunching() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.first.unknown = true; terminal.first.rejectScreen = true;
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("unknown state inserts", () -> terminal.first.sends == 1, 5);
        assertEquals(0, terminal.first.executions);
    }
    public void testFailedProbeDoesNotBecomeIdleOrLaunch() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.first.shell = true; terminal.first.failProbe = true; terminal.first.rejectScreen = true;
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("failed probe inserts on live connection", () -> terminal.first.sends == 1, 5);
        assertEquals(0, terminal.first.executions);
    }
    public void testTypedShellInputIsPreserved() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.first.shell = true; terminal.first.typedShell = true;
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("idle decision completed", () -> terminal.first.connectionReads >= 2, 5);
        assertEquals(0, terminal.first.sends); assertEquals(0, terminal.first.executions);
    }
    public void testConnectionReplacementDuringProbeCancelsInsertion() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        var blocked = new java.util.concurrent.CountDownLatch(1);
        terminal.first.blockedProbe = blocked;
        try {
            getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
            PlatformTestUtil.waitWithEventsDispatching("probe started", () -> terminal.first.probes.get() > 0, 5);
            terminal.first.connection = new Object();
            blocked.countDown();
            PlatformTestUtil.waitWithEventsDispatching("replacement checked", () -> terminal.first.connectionReads >= 2, 5);
            assertEquals(0, terminal.first.sends); assertEquals(0, terminal.first.executions);
        } finally { blocked.countDown(); }
    }
    public void testTabMoveDuringProbeDoesNotRetargetEvenIfMovedBack() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        var blocked = new java.util.concurrent.CountDownLatch(1);
        terminal.first.blockedProbe = blocked;
        try {
            getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
            PlatformTestUtil.waitWithEventsDispatching("probe started", () -> terminal.first.probes.get() > 0, 5);
            terminal.move(); terminal.front = terminal.first;
            blocked.countDown();
            // A new request proves event dispatch has continued; the invalidated one cannot send.
            terminal.first.blockedProbe = null;
            getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
            PlatformTestUtil.waitWithEventsDispatching("new explicit request", () -> terminal.first.sends == 1, 5);
            assertEquals(1, terminal.first.input.size()); assertTrue(terminal.second.input.isEmpty());
        } finally { blocked.countDown(); }
    }
    public void testDisconnectedSessionNeitherInsertsNorLaunches() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.first.connected = false;
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        assertEquals(0, terminal.first.probes.get());
        assertEquals(0, terminal.first.sends); assertEquals(0, terminal.first.executions);
    }
    public void testSendFailureDoesNotRetry() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.first.failSend = true;
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor()); sessions.insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("single failed send", () -> terminal.first.sends == 1, 5);
        terminal.first.failSend = false;
        sessions.insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("explicit new request succeeds", () -> terminal.first.input.size() == 1, 5);
        assertEquals(2, terminal.first.sends);
    }
    public void testMissingTabLaunchesAndInsertsOriginalReference() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.front = null;
        var profile = new AgentProfile("Custom", AgentProfile.Kind.Custom, "custom");
        profile.directory = System.getProperty("java.io.tmpdir");
        var profiles = AgentProfiles.getInstance(getProject()).getState();
        profiles.profiles = new ArrayList<>(List.of(profile)); profiles.defaultId = profile.id;
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("created tab launches", () -> terminal.first.executions == 1, 5);
        assertEquals(1, terminal.created); assertTrue(terminal.first.input.isEmpty());
        terminal.first.rejectScreen = true;
        PlatformTestUtil.waitWithEventsDispatching("created tab receives original reference", () -> terminal.first.sends == 1, 5);
        assertEquals(1, terminal.first.executions);
    }
    public void testNewTabWaitsForConnectionBeforeLaunchingAndInserting() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.front = null; terminal.first.connected = false;
        setDefaultProfile();
        AgentProfiles.getInstance(getProject()).getState().profiles.getFirst().directory = System.getProperty("java.io.tmpdir");
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("new shell probed", () -> terminal.first.probes.get() > 0, 5);
        assertEquals(0, terminal.first.executions); assertEquals(0, terminal.first.sends);
        terminal.first.connected = true;
        PlatformTestUtil.waitWithEventsDispatching("connected shell launches and receives reference", () -> terminal.first.sends == 1, 5);
        assertEquals(1, terminal.created); assertEquals(1, terminal.first.executions);
    }
    public void testFailedLaunchNeverInsertsReferenceIntoShell() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.first.shell = true; terminal.first.exitAfterLaunch = true;
        setDefaultProfile();
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("launch attempted", () -> terminal.first.executions == 1, 5);
        long launched = System.nanoTime();
        PlatformTestUtil.waitWithEventsDispatching("startup check completed", () -> System.nanoTime() - launched
                > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2500), 5);
        assertEquals(0, terminal.first.sends); assertEquals(1, terminal.first.executions);
    }
    public void testDisablingDuringStartupDropsOriginalReference() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.first.shell = true;
        setDefaultProfile();
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("launch attempted", () -> terminal.first.executions == 1, 5);
        sessions.cancelAll();
        AgentSettings.getInstance().getState().enabled = false;
        long cancelled = System.nanoTime();
        PlatformTestUtil.waitWithEventsDispatching("past startup delay", () -> System.nanoTime() - cancelled
                > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2500), 5);
        assertEquals(0, terminal.first.sends);
    }
    public void testUnknownAfterLaunchWaitsForConfirmedRunningProcess() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.first.shell = true;
        setDefaultProfile();
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("launch attempted", () -> terminal.first.executions == 1, 5);
        terminal.first.unknown = true; terminal.first.rejectScreen = true;
        long launched = System.nanoTime();
        PlatformTestUtil.waitWithEventsDispatching("past startup delay", () -> System.nanoTime() - launched
                > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2500), 5);
        assertEquals(0, terminal.first.sends);
        terminal.first.unknown = false;
        PlatformTestUtil.waitWithEventsDispatching("confirmed process receives original reference", () -> terminal.first.sends == 1, 5);
        assertEquals(1, terminal.first.executions);
    }
    public void testDelayedShowCoalescesAndReusesAutomaticallyInitializedTab() throws Exception {
        var terminal = setupTerminal(); openDiskFile("one\ntwo\n");
        terminal.front = null; terminal.delayShow = true;
        setDefaultProfile();
        var editor = myFixture.getEditor(); editor.getSelectionModel().setSelection(4, 7);
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(editor); sessions.insert(editor);
        assertEquals(1, terminal.shows); assertEquals(0, terminal.created);
        editor.getSelectionModel().removeSelection();
        terminal.front = terminal.first; terminal.first.shell = true;
        terminal.finishShow();
        PlatformTestUtil.waitWithEventsDispatching("automatic tab receives captured selection", () -> terminal.first.sends == 1, 5);
        assertEquals(0, terminal.created); assertEquals(1, terminal.first.executions);
        assertTrue(terminal.first.input.getFirst().contains("#L2"));
    }
    public void testDelayedShowCreatesOnlyOneTabForRepeatedShortcut() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.front = null; terminal.delayShow = true; setDefaultProfile();
        AgentProfiles.getInstance(getProject()).getState().profiles.getFirst().directory = System.getProperty("java.io.tmpdir");
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor()); sessions.insert(myFixture.getEditor());
        terminal.finishShow(); sessions.insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("one tab and one insertion", () -> terminal.first.sends == 1, 5);
        assertEquals(1, terminal.created); assertEquals(1, terminal.first.executions);
    }
    public void testCancelBeforeShowCallbackDoesNotCreateOrSend() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.front = null; terminal.delayShow = true;
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor()); sessions.cancelAll();
        terminal.finishShow();
        assertEquals(0, terminal.created); assertEquals(0, terminal.first.executions);
        assertEquals(0, terminal.first.sends);
    }
    public void testExistingTabReplacedWhileShowingIsNotRetargeted() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.delayShow = true;
        getProject().getService(AgentSessions.class).insert(myFixture.getEditor());
        terminal.move(); terminal.finishShow();
        assertEquals(0, terminal.first.sends); assertEquals(0, terminal.second.sends);
        assertEquals(0, terminal.created);
    }
    public void testInitialNullConnectionCanBecomeConnectedWithoutReplacingTab() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); setDefaultProfile();
        terminal.first.connection = null; terminal.first.connected = false; terminal.first.shell = true;
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor());
        PlatformTestUtil.waitWithEventsDispatching("waiting for initial connection", () -> terminal.first.probes.get() > 0, 5);
        sessions.insert(myFixture.getEditor());
        terminal.first.connection = new Object(); terminal.first.connected = true;
        PlatformTestUtil.waitWithEventsDispatching("initial connection launches once", () -> terminal.first.sends == 1, 5);
        assertEquals(0, terminal.created); assertEquals(1, terminal.first.executions);
    }
    public void testOldShowCallbackCannotCompleteANewRequestAfterCancellation() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.delayShow = true;
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor());
        Runnable oldCallback = terminal.shown;
        sessions.cancelAll();
        sessions.insert(myFixture.getEditor());
        oldCallback.run();
        assertEquals(0, terminal.first.probes.get());
        terminal.finishShow();
        PlatformTestUtil.waitWithEventsDispatching("new show completes", () -> terminal.first.sends == 1, 5);
        assertEquals(1, terminal.first.input.size());
    }
    public void testDifferentReferencesShareOneNewTabAndLaunch() throws Exception {
        var terminal = setupTerminal(); openDiskFile("one\ntwo\n");
        terminal.front = null; terminal.delayShow = true; setDefaultProfile();
        AgentProfiles.getInstance(getProject()).getState().profiles.getFirst().directory = System.getProperty("java.io.tmpdir");
        var editor = myFixture.getEditor();
        var sessions = getProject().getService(AgentSessions.class);
        editor.getSelectionModel().setSelection(0, 3); sessions.insert(editor);
        editor.getSelectionModel().setSelection(4, 7); sessions.insert(editor);
        assertEquals(1, terminal.shows);
        terminal.finishShow();
        PlatformTestUtil.waitWithEventsDispatching("both captured references delivered", () -> terminal.first.sends == 2, 5);
        assertEquals(1, terminal.created); assertEquals(1, terminal.first.executions);
        assertTrue(terminal.first.input.stream().anyMatch(t -> t.contains("#L1")));
        assertTrue(terminal.first.input.stream().anyMatch(t -> t.contains("#L2")));
    }
    public void testTypedInputInAutomaticallyInitializedTabIsPreserved() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.front = null; terminal.delayShow = true;
        var sessions = getProject().getService(AgentSessions.class);
        sessions.insert(myFixture.getEditor());
        terminal.front = terminal.first; terminal.first.shell = true; terminal.first.typedShell = true;
        terminal.finishShow();
        PlatformTestUtil.waitWithEventsDispatching("shell inspected", () -> terminal.first.connectionReads > 2, 5);
        sessions.cancelAll();
        assertEquals(0, terminal.created); assertEquals(0, terminal.first.executions); assertEquals(0, terminal.first.sends);
    }
    private void setDefaultProfile() {
        var profile = new AgentProfile("Claude", AgentProfile.Kind.Claude, "claude");
        var profiles = AgentProfiles.getInstance(getProject()).getState();
        profiles.profiles = new ArrayList<>(List.of(profile)); profiles.defaultId = profile.id;
    }
    public void testMultipleCaretsCapturedBeforeProbeCompletes() throws Exception {
        var terminal = setupTerminal(); openDiskFile("one\ntwo\nthree\n");
        var editor = myFixture.getEditor();
        editor.getSelectionModel().setSelection(0, 3);
        var second = editor.getCaretModel().addCaret(new com.intellij.openapi.editor.VisualPosition(2, 0));
        assertNotNull(second); second.setSelection(8, 13);
        getProject().getService(AgentSessions.class).insert(editor);
        editor.getCaretModel().removeSecondaryCarets(); editor.getSelectionModel().removeSelection();
        PlatformTestUtil.waitWithEventsDispatching("captured carets inserted", () -> terminal.first.sends == 1, 5);
        String sent = terminal.first.input.getFirst();
        assertTrue(sent.contains("#L1")); assertTrue(sent.contains("#L3"));
        assertFalse(sent.contains("\n")); assertFalse(sent.contains("\r"));
    }
    private void openDiskFile(String text) throws Exception {
        Path path = Files.createTempFile("snakeorcamel-agent-", ".txt").toRealPath(); Files.writeString(path,text);
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(),path.getParent().toString());
        Disposer.register(getTestRootDisposable(), () -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path); assertNotNull(file);
        myFixture.configureFromExistingVirtualFile(file);
    }
    private FakeTerminal setupTerminal() {
        var terminal = new FakeTerminal();
        ServiceContainerUtil.replaceService(getProject(),TerminalAccess.class,terminal,getTestRootDisposable());
        AgentSettings.getInstance().getState().enabled = true;
        return terminal;
    }
    private static final class FakeTerminal implements TerminalAccess {
        final FakeSession first = new FakeSession(), second = new FakeSession();
        Session front = first; int created; final List<Runnable> watchers = new ArrayList<>();
        boolean delayShow; int shows; Runnable shown;
        @Override public void show(Runnable ready) {
            shows++;
            if (delayShow) shown = ready; else ready.run();
        }
        void finishShow() { var callback = shown; shown = null; callback.run(); }
        @Override public Session first() { return front; }
        @Override public Session create(Path directory,String name) { created++; front=first; first.shell=true; first.ready=false; return first; }
        @Override public boolean isFirst(Session session) { return front == session; }
        @Override public AutoCloseable watchFirst(Session session,Runnable invalidated) { watchers.add(invalidated); return () -> watchers.remove(invalidated); }
        void move() { front=second; List.copyOf(watchers).forEach(Runnable::run); }
    }
    private static final class FakeSession implements TerminalAccess.Session {
        final List<String> input = new ArrayList<>(); Object connection=new Object();
        final java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        volatile java.util.concurrent.CountDownLatch blockedProbe;
        boolean ready=true, shell, compact, unknown, failProbe, rejectScreen, typedShell, failSend;
        boolean connected=true, exitAfterLaunch;
        int executions, connectionReads, sends, focuses;
        @Override public Object identity() { return this; }
        @Override public void refreshProcessState() {
            var application = com.intellij.openapi.application.ApplicationManager.getApplication();
            assertFalse(application.isDispatchThread());
            assertFalse(application.isReadAccessAllowed());
            var blocked = blockedProbe;
            probes.incrementAndGet();
            if (failProbe) throw new IllegalStateException("Process lookup failed");
            if (blocked != null) {
                try { assertTrue(blocked.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
        }
        @Override public TerminalAccess.Connection connection() {
            connectionReads++;
            return new TerminalAccess.Connection(connection, connected, unknown ? TerminalAccess.ProcessStatus.UNKNOWN
                    : shell ? TerminalAccess.ProcessStatus.IDLE : TerminalAccess.ProcessStatus.RUNNING);
        }
        @Override public TerminalAccess.Screen screen() {
            if (rejectScreen) throw new AssertionError("Running/unknown insertion must not read terminal text");
            com.intellij.openapi.application.ApplicationManager.getApplication().assertIsDispatchThread();
            String text = compact
                    ? "Previous response\n› existing question\nGPT-6.1-Sol high fast · ~/project\n← for agents · ? for shortcuts"
                    : "OpenAI Codex\n› existing question\nGPT-6.1-Sol high · /tmp";
            return new TerminalAccess.Screen(ready ? text : "starting",ready ? "› existing question" : "",ready ? "› existing question" : "",2,true,shell && !typedShell,!shell,connection);
        }
        @Override public Path directory() { return null; }
        @Override public void execute(String command) { executions++; shell=exitAfterLaunch; }
        @Override public void insert(String text) { sends++; if (failSend) throw new IllegalStateException("send failed"); input.add(text); }
        @Override public void focus() { focuses++; }
        @Override public boolean exists() { return true; }
    }
    private record Marker(com.intellij.openapi.editor.RangeMarker range) implements AutoCloseable {
        @Override public void close() { range.dispose(); }
    }
}
