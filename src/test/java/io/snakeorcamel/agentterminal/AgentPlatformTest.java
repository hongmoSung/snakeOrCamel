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
        PlatformTestUtil.waitWithEventsDispatching("reference inserted after readiness",() -> !terminal.first.input.isEmpty(),5);
        assertEquals(1,terminal.first.executions);
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
    public void testConflictingShortcutIsPreservedAndAutomaticBindingIsRemovedOnDisable() {
        var keymap = com.intellij.openapi.keymap.KeymapManager.getInstance().getActiveKeymap();
        var shortcut = AgentKeymap.shortcut();
        var original = keymap.getShortcuts(AgentSettings.INSERT_ACTION);
        String conflicting = "SnakeOrCamel.TestConflict";
        try {
            AgentSettings.getInstance().getState().enabled = true;
            keymap.addShortcut(conflicting,shortcut);
            AgentKeymap.getInstance().reconcile();
            assertTrue(AgentKeymap.conflicts(keymap).contains(conflicting));
            assertTrue(Arrays.asList(keymap.getShortcuts(conflicting)).contains(shortcut));
            keymap.removeShortcut(conflicting,shortcut);
            if (AgentKeymap.conflicts(keymap).isEmpty() && original.length == 0) {
                AgentKeymap.getInstance().reconcile();
                assertTrue(Arrays.asList(keymap.getShortcuts(AgentSettings.INSERT_ACTION)).contains(shortcut));
                AgentSettings.getInstance().getState().enabled = false;
                AgentKeymap.getInstance().reconcile();
                assertEquals(0,keymap.getShortcuts(AgentSettings.INSERT_ACTION).length);
            }
        } finally {
            keymap.removeShortcut(conflicting,shortcut);
            AgentSettings.getInstance().getState().enabled = false;
            AgentKeymap.getInstance().reconcile();
        }
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
        Session front = first; int created; Runnable moved;
        @Override public Session first() { return front; }
        @Override public Session create(Path directory,String name) { created++; first.shell=true; first.ready=false; return first; }
        @Override public boolean isFirst(Session session) { return front == session; }
        @Override public AutoCloseable watchFirst(Session session,Runnable invalidated) { moved=invalidated; return () -> moved=null; }
        void move() { front=second; if (moved != null) moved.run(); }
    }
    private static final class FakeSession implements TerminalAccess.Session {
        final List<String> input = new ArrayList<>(); final Object connection=new Object();
        final java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        volatile java.util.concurrent.CountDownLatch blockedProbe;
        boolean ready=true, shell, compact; int executions;
        @Override public Object identity() { return this; }
        @Override public void refreshProcessState() {
            var application = com.intellij.openapi.application.ApplicationManager.getApplication();
            assertFalse(application.isDispatchThread());
            assertFalse(application.isReadAccessAllowed());
            var blocked = blockedProbe;
            probes.incrementAndGet();
            if (blocked != null) {
                try { assertTrue(blocked.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
        }
        @Override public TerminalAccess.Screen screen() {
            com.intellij.openapi.application.ApplicationManager.getApplication().assertIsDispatchThread();
            String text = compact
                    ? "Previous response\n› existing question\nGPT-6.1-Sol high fast · ~/project\n← for agents · ? for shortcuts"
                    : "OpenAI Codex\n› existing question\nGPT-6.1-Sol high · /tmp";
            return new TerminalAccess.Screen(ready ? text : "starting",ready ? "› existing question" : "",ready ? "› existing question" : "",2,true,shell,!shell,connection);
        }
        @Override public Path directory() { return null; }
        @Override public void execute(String command) { executions++; shell=false; }
        @Override public void insert(String text) { input.add(text); }
        @Override public void focus() {}
        @Override public boolean exists() { return true; }
    }
    private record Marker(com.intellij.openapi.editor.RangeMarker range) implements AutoCloseable {
        @Override public void close() { range.dispose(); }
    }
}
