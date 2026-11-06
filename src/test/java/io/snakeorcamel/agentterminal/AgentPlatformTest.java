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
    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1);
    private AgentSessions sessions;
    private AgentProfile profile;
    private FakeTerminal setupTerminal() {
        var terminal = new FakeTerminal();
        ServiceContainerUtil.replaceService(getProject(), TerminalAccess.class, terminal, getTestRootDisposable());
        sessions = new AgentSessions(getProject(), now::get);
        ServiceContainerUtil.replaceService(getProject(), AgentSessions.class, sessions, getTestRootDisposable());
        AgentSettings.getInstance().getState().enabled = true;
        profile = new AgentProfile("Codex", AgentProfile.Kind.Codex, "codex");
        profile.directory = System.getProperty("java.io.tmpdir");
        var state = AgentProfiles.getInstance(getProject()).getState();
        state.profiles = new ArrayList<>(List.of(profile)); state.defaultId = profile.id;
        return terminal;
    }
    private void advance(long seconds) { now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds)); }
    private void waitFor(String label, java.util.function.BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching(label, condition, 5);
    }
    private FakeSession launch(FakeTerminal terminal) {
        sessions.run(profile);
        var owned = terminal.created.getLast();
        waitFor("one launch", () -> owned.executions == 1);
        advance(3);
        waitFor("startup confirmed", () -> terminal.watchers.isEmpty());
        return owned;
    }
    public void testGeneralShellIsPreservedAndDedicatedReferenceIsInsertedOnceWithoutEnter() throws Exception {
        var terminal = setupTerminal(); openDiskFile("first\nsecond\nthird\n");
        terminal.general.typedShell = true;
        var editor = myFixture.getEditor(); editor.getSelectionModel().setSelection(0,13);
        sessions.insert(editor); sessions.insert(editor);
        var owned = terminal.created.getFirst();
        waitFor("dedicated launch", () -> owned.executions == 1);
        assertTrue(owned.input.isEmpty()); advance(3);
        waitFor("reference inserted", () -> owned.sends == 1);
        assertEquals(1, terminal.created.size()); assertEquals(1, owned.executions);
        assertTrue(owned.input.getFirst().contains("#L1-L2"));
        assertFalse(owned.input.getFirst().contains("\n")); assertFalse(owned.input.getFirst().contains("\r"));
        assertTrue(terminal.general.input.isEmpty()); assertEquals(0,terminal.general.executions);
        assertEquals(0, editor.getSelectionModel().getSelectionStart());
        assertEquals(13, editor.getSelectionModel().getSelectionEnd());
    }
    public void testRunAndInsertJoinTheSameStartupInEitherOrder() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.delayShow = true;
        sessions.run(profile); sessions.insert(myFixture.getEditor()); sessions.run(profile);
        assertEquals(1, terminal.created.size()); assertEquals(1, terminal.shows);
        terminal.finishShow(); var first = terminal.created.getFirst();
        waitFor("launch", () -> first.executions == 1); advance(3);
        waitFor("insert", () -> first.sends == 1);
        first.shell = true; // A confirmed agent exited. The old shell remains open.
        sessions.insert(myFixture.getEditor()); sessions.run(profile);
        terminal.finishShow();
        waitFor("replacement created", () -> terminal.created.size() == 2);
        var second = terminal.created.getLast();
        waitFor("replacement launch", () -> second.executions == 1); advance(3);
        waitFor("replacement insert", () -> second.sends == 1);
        assertEquals(2, terminal.created.size()); assertTrue(first.alive); assertEquals(1, first.executions);
    }
    public void testRunningOwnedTabIsReusedAfterReorderRenameAndSplitMove() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal);
        terminal.front = terminal.general; owned.name = "renamed";
        sessions.insert(myFixture.getEditor());
        terminal.move(owned); // Ownership is session-based; this is not a close event.
        waitFor("reuse moved tab", () -> owned.sends == 1);
        sessions.run(profile);
        waitFor("run reuse completes", () -> terminal.watchers.isEmpty());
        assertEquals(1, terminal.created.size()); assertEquals(1, owned.executions);
        assertSame(owned, terminal.front); assertTrue(terminal.general.input.isEmpty());
    }
    public void testExitedAgentPreservesTypedShellAndCreatesOneReplacement() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var old = launch(terminal);
        old.shell = true; old.typedShell = true;
        sessions.insert(myFixture.getEditor()); sessions.insert(myFixture.getEditor());
        waitFor("replacement created", () -> terminal.created.size() == 2);
        var fresh = terminal.created.getLast(); waitFor("replacement launched", () -> fresh.executions == 1);
        advance(3); waitFor("replacement inserted", () -> fresh.sends == 1);
        assertTrue(old.alive); assertTrue(old.typedShell); assertEquals(0, old.sends); assertEquals(1, old.executions);
    }
    public void testClosedOwnedTabCreatesANewDedicatedTab() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var old = launch(terminal);
        terminal.close(old); sessions.insert(myFixture.getEditor());
        var fresh = terminal.created.getLast(); waitFor("new launch", () -> fresh.executions == 1);
        advance(3); waitFor("new insert", () -> fresh.sends == 1);
        assertEquals(2, terminal.created.size()); assertEquals(0, old.sends);
    }
    public void testDifferentReferencesAreDeliveredInOrderAndDuplicatesCoalesce() throws Exception {
        var terminal = setupTerminal(); openDiskFile("one\ntwo\nthree\n"); terminal.delayShow = true;
        var editor = myFixture.getEditor();
        editor.getSelectionModel().setSelection(0,3); sessions.insert(editor); sessions.insert(editor);
        editor.getSelectionModel().setSelection(4,7); sessions.insert(editor);
        editor.getSelectionModel().removeSelection(); terminal.finishShow();
        var owned = terminal.created.getFirst(); waitFor("launch", () -> owned.executions == 1); advance(3);
        waitFor("two references", () -> owned.sends == 2);
        assertEquals(1, terminal.created.size()); assertEquals(1, owned.executions);
        assertTrue(owned.input.get(0).contains("#L1")); assertTrue(owned.input.get(1).contains("#L2"));
    }
    public void testMultipleCaretsCapturedBeforeProbeCompletes() throws Exception {
        var terminal = setupTerminal(); openDiskFile("one\ntwo\nthree\n");
        var editor = myFixture.getEditor(); editor.getSelectionModel().setSelection(0,3);
        var second = editor.getCaretModel().addCaret(new com.intellij.openapi.editor.VisualPosition(2,0));
        assertNotNull(second); second.setSelection(8,13);
        sessions.insert(editor); editor.getCaretModel().removeSecondaryCarets(); editor.getSelectionModel().removeSelection();
        var owned = terminal.created.getFirst(); waitFor("launch", () -> owned.executions == 1); advance(3);
        waitFor("captured carets", () -> owned.sends == 1);
        assertTrue(owned.input.getFirst().contains("#L1")); assertTrue(owned.input.getFirst().contains("#L3"));
    }
    public void testNewReworkedTabQueuesBeforeShowWithoutShellIntegration() throws Exception {
        var terminal = setupTerminal(); openDiskFile("one\ntwo\n"); terminal.bufferInitialCommand = true;
        terminal.configure = s -> { s.connected = false; s.connection = null; s.rejectScreen = true; };
        myFixture.getEditor().getSelectionModel().setSelection(4,7);
        sessions.insert(myFixture.getEditor()); sessions.run(profile); sessions.insert(myFixture.getEditor());
        var owned = terminal.created.getFirst(); assertEquals(1, owned.executions);
        waitFor("disconnected probe", () -> owned.probes.get() > 0); advance(5);
        assertEquals(0, owned.sends);
        owned.connection = new Object(); owned.connected = true;
        int probes = owned.probes.get(); waitFor("connection observed", () -> owned.probes.get() > probes + 1);
        assertEquals(0, owned.sends); advance(3);
        waitFor("buffered startup inserts", () -> owned.sends == 1);
        assertTrue(owned.input.getFirst().contains("#L2")); assertEquals(1, owned.executions);
    }
    public void testClassicWaitsForInitialConnectionAndEmptyInput() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.configure = s -> { s.connection = null; s.connected = false; s.typedShell = true; };
        sessions.insert(myFixture.getEditor()); var owned = terminal.created.getFirst();
        waitFor("probe disconnected", () -> owned.probes.get() > 0); assertEquals(0, owned.executions);
        owned.connection = new Object(); owned.connected = true;
        int probes = owned.probes.get(); waitFor("typed input checked", () -> owned.probes.get() > probes + 1);
        assertEquals(0, owned.executions); owned.typedShell = false;
        waitFor("empty shell launches", () -> owned.executions == 1); advance(3);
        waitFor("connected insert", () -> owned.sends == 1);
    }
    public void testUnknownOwnedProcessTimesOutWithoutSendingLaunchingOrReplacing() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal);
        owned.unknown = true; owned.rejectScreen = true; sessions.insert(myFixture.getEditor());
        int probes = owned.probes.get(); waitFor("unknown sampled", () -> owned.probes.get() > probes);
        advance(29); sessions.insert(myFixture.getEditor()); // Does not extend the first request.
        advance(2); waitFor("original deadline", () -> terminal.watchers.isEmpty());
        owned.unknown = false;
        assertEquals(0, owned.sends); assertEquals(1, owned.executions); assertEquals(1, terminal.created.size());
    }
    public void testFailedProbeWaitsAndDoesNotTreatShellAsIdle() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal);
        owned.shell = true; owned.failProbe = true; owned.rejectScreen = true;
        sessions.insert(myFixture.getEditor()); int probes = owned.probes.get();
        waitFor("failed probe", () -> owned.probes.get() > probes); advance(31);
        waitFor("failed query expires", () -> terminal.watchers.isEmpty());
        assertEquals(0, owned.sends); assertEquals(1, owned.executions); assertEquals(1, terminal.created.size());
    }
    public void testUnknownStartupWaitsForRunningConfirmation() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        terminal.bufferInitialCommand = true; terminal.configure = s -> s.unknown = true;
        sessions.insert(myFixture.getEditor()); var owned = terminal.created.getFirst(); advance(3);
        waitFor("unknown probed", () -> owned.probes.get() > 1); assertEquals(0, owned.sends);
        owned.unknown = false; owned.rejectScreen = true;
        waitFor("confirmed running", () -> owned.sends == 1); assertEquals(1, owned.executions);
    }
    public void testFailedInitialLaunchNeverInsertsOrRetriesInTheSameRequest() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.configure = s -> s.exitAfterLaunch = true;
        sessions.insert(myFixture.getEditor()); sessions.insert(myFixture.getEditor());
        var owned = terminal.created.getFirst(); waitFor("failed launch attempted", () -> owned.executions == 1); advance(3);
        waitFor("failed startup completes", () -> terminal.watchers.isEmpty());
        assertEquals(0, owned.sends); assertEquals(1, owned.executions); assertEquals(1, terminal.created.size()); assertTrue(owned.alive);
    }
    public void testBufferedInitialLaunchFailureNeverInsertsIntoShell() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.bufferInitialCommand = true;
        terminal.configure = s -> s.exitAfterLaunch = true;
        sessions.insert(myFixture.getEditor()); advance(3);
        waitFor("buffered launch failure", () -> terminal.watchers.isEmpty());
        assertEquals(1, terminal.created.size()); assertEquals(1, terminal.created.getFirst().executions);
        assertEquals(0, terminal.created.getFirst().sends);
    }
    public void testExecuteFailureIsNotRetried() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.configure = s -> s.failExecute = true;
        sessions.insert(myFixture.getEditor()); var owned = terminal.created.getFirst();
        waitFor("failed execute", () -> owned.executions == 1 && terminal.watchers.isEmpty());
        assertEquals(1, terminal.created.size()); assertEquals(0, owned.sends);
    }
    public void testConnectionReplacementDuringProbeDiscardsReference() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal);
        var blocked = new java.util.concurrent.CountDownLatch(1); owned.blockedProbe = blocked;
        try {
            int probes = owned.probes.get(); sessions.insert(myFixture.getEditor());
            waitFor("probe started", () -> owned.probes.get() > probes);
            owned.connection = new Object(); blocked.countDown();
            waitFor("replacement cancels", () -> terminal.watchers.isEmpty());
            assertEquals(0, owned.sends); assertEquals(1, terminal.created.size());
        } finally { blocked.countDown(); }
    }
    public void testActualTabCloseDuringProbeDiscardsLateResult() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal);
        var blocked = new java.util.concurrent.CountDownLatch(1); owned.blockedProbe = blocked;
        try {
            int probes = owned.probes.get(); sessions.insert(myFixture.getEditor());
            waitFor("probe started", () -> owned.probes.get() > probes); terminal.close(owned); blocked.countDown();
            waitFor("close cancels", () -> terminal.watchers.isEmpty()); assertEquals(0, owned.sends);
        } finally { blocked.countDown(); }
    }
    public void testDisableAndReenableDiscardsLateResult() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal);
        var blocked = new java.util.concurrent.CountDownLatch(1); owned.blockedProbe = blocked;
        try {
            int probes = owned.probes.get(); sessions.insert(myFixture.getEditor());
            waitFor("probe started", () -> owned.probes.get() > probes);
            AgentKeymap.getInstance().applyEnabled(false);
            owned.blockedProbe = null; AgentKeymap.getInstance().applyEnabled(true);
            blocked.countDown(); sessions.insert(myFixture.getEditor());
            waitFor("only new request sends", () -> owned.sends == 1);
            assertEquals(1, owned.input.size()); assertEquals(1, owned.executions);
        } finally { blocked.countDown(); }
    }
    public void testCancelWhileShowingIgnoresOldCallbackAndBlockedQuery() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.delayShow = true;
        sessions.insert(myFixture.getEditor()); Runnable old = terminal.shown;
        sessions.cancelAll(); sessions.insert(myFixture.getEditor()); old.run();
        var owned = terminal.created.getFirst(); assertEquals(0, owned.probes.get());
        terminal.finishShow(); waitFor("new request launch", () -> owned.executions == 1); advance(3);
        waitFor("new request sends", () -> owned.sends == 1);
        assertEquals(1, terminal.created.size());
    }
    public void testTimeoutWhileShowingDoesNotRunLate() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.delayShow = true;
        sessions.insert(myFixture.getEditor()); advance(31);
        sessions.insert(myFixture.getEditor()); // Expires the original work without extending or recreating it.
        terminal.finishShow(); var owned = terminal.created.getFirst();
        assertEquals(0, owned.executions); assertEquals(0, owned.sends); assertTrue(terminal.watchers.isEmpty());
    }
    public void testSendFailureDoesNotAutomaticallyRetryOrDropOwnership() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal); owned.failSend = true;
        sessions.insert(myFixture.getEditor()); sessions.insert(myFixture.getEditor());
        waitFor("single failed send", () -> owned.sends == 1 && terminal.watchers.isEmpty());
        owned.failSend = false; sessions.insert(myFixture.getEditor());
        waitFor("explicit retry", () -> owned.input.size() == 1);
        assertEquals(2, owned.sends); assertEquals(1, owned.executions); assertEquals(1, terminal.created.size());
    }
    public void testProfileNameChangeReusesButCommandAndDirectoryChangesCreateTabs() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var first = launch(terminal);
        profile.name = "Renamed"; sessions.run(profile);
        waitFor("rename reuse", () -> terminal.watchers.isEmpty()); assertEquals(1, terminal.created.size());
        profile.command = "codex --other"; var second = launch(terminal);
        assertEquals("codex --other", second.command); assertTrue(first.alive);
        Path dir = Files.createTempDirectory("agent-directory-");
        Disposer.register(getTestRootDisposable(), () -> { try { Files.deleteIfExists(dir); } catch (Exception ignored) {} });
        profile.directory = dir.toString(); var third = launch(terminal);
        assertEquals(dir.toAbsolutePath().normalize(), third.directory); assertTrue(second.alive);
        assertEquals(3, terminal.created.size());
    }
    public void testDefaultProfileChangeDoesNotRetargetAcceptedRequests() throws Exception {
        var terminal = setupTerminal(); openDiskFile("one\ntwo\n"); terminal.delayShow = true;
        myFixture.getEditor().getSelectionModel().setSelection(0,3); sessions.insert(myFixture.getEditor());
        var originalShow = terminal.shown;
        var other = new AgentProfile("Claude", AgentProfile.Kind.Claude, "claude"); other.directory = profile.directory;
        var state = AgentProfiles.getInstance(getProject()).getState(); state.profiles.add(other); state.defaultId = other.id;
        myFixture.getEditor().getSelectionModel().setSelection(4,7); sessions.insert(myFixture.getEditor());
        originalShow.run(); terminal.finishShow();
        var first = terminal.created.getFirst(); var second = terminal.created.getLast();
        waitFor("both launches", () -> first.executions == 1 && second.executions == 1); advance(3);
        waitFor("original destinations", () -> first.sends == 1 && second.sends == 1);
        assertEquals("codex", first.command); assertEquals("claude", second.command);
        assertTrue(first.input.getFirst().contains("#L1")); assertTrue(second.input.getFirst().contains("#L2"));
    }
    public void testProfileCommandSnapshotIsKeptDuringStartup() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); terminal.delayShow = true;
        sessions.insert(myFixture.getEditor()); profile.command = "changed"; terminal.finishShow();
        var owned = terminal.created.getFirst(); waitFor("snapshot launch", () -> owned.executions == 1);
        assertEquals("codex", owned.command); advance(3); waitFor("snapshot insert", () -> owned.sends == 1);
    }
    public void testNewProjectSessionDoesNotAdoptRestoredOrNamedTabs() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var old = launch(terminal);
        sessions.dispose(); sessions = new AgentSessions(getProject(), now::get);
        ServiceContainerUtil.replaceService(getProject(), AgentSessions.class, sessions, getTestRootDisposable());
        sessions.insert(myFixture.getEditor()); var fresh = terminal.created.getLast();
        waitFor("new project session launch", () -> fresh.executions == 1); advance(3);
        waitFor("new session insert", () -> fresh.sends == 1);
        assertEquals(2, terminal.created.size()); assertTrue(old.alive); assertEquals(0, old.sends);
    }
    public void testAllProfileKindsUseTheSameDedicatedStartup() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        for (var kind : AgentProfile.Kind.values()) {
            var next = new AgentProfile(kind.name(), kind, kind.name().toLowerCase()); next.directory = profile.directory;
            var state = AgentProfiles.getInstance(getProject()).getState(); state.profiles.add(next); state.defaultId = next.id;
            sessions.insert(myFixture.getEditor()); var owned = terminal.created.getLast();
            waitFor("kind launch", () -> owned.executions == 1); advance(3);
            waitFor("kind insert", () -> owned.sends == 1); assertEquals(next.command, owned.command);
        }
        assertEquals(3, terminal.created.size()); assertEquals(0, terminal.general.executions);
    }
    public void testEditedProfileQueuesNewConfigurationBehindOriginalStartup() throws Exception {
        var terminal = setupTerminal(); openDiskFile("one\ntwo\n"); terminal.delayShow = true;
        myFixture.getEditor().getSelectionModel().setSelection(0,3); sessions.insert(myFixture.getEditor());
        profile.command = "codex --changed";
        myFixture.getEditor().getSelectionModel().setSelection(4,7); sessions.insert(myFixture.getEditor());
        assertEquals(1, terminal.created.size()); terminal.finishShow();
        var original = terminal.created.getFirst(); waitFor("original command", () -> original.executions == 1);
        assertEquals("codex", original.command); advance(3);
        waitFor("original delivered then new startup", () -> original.sends == 1 && terminal.created.size() == 2);
        terminal.finishShow(); var edited = terminal.created.getLast();
        waitFor("edited command", () -> edited.executions == 1); advance(3);
        waitFor("edited delivered", () -> edited.sends == 1);
        assertEquals("codex --changed", edited.command);
        assertTrue(original.input.getFirst().contains("#L1")); assertTrue(edited.input.getFirst().contains("#L2"));
    }
    public void testMissingDefaultCancellationCreatesNoTabOrCommand() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        AgentProfiles.getInstance(getProject()).getState().defaultId = "";
        sessions.dispose();
        sessions = new AgentSessions(getProject(), now::get, choices -> null);
        ServiceContainerUtil.replaceService(getProject(), AgentSessions.class, sessions, getTestRootDisposable());
        sessions.insert(myFixture.getEditor());
        assertTrue(terminal.created.isEmpty()); assertEquals(0, terminal.shows);
        assertEquals("", AgentProfiles.getInstance(getProject()).getState().defaultId);
        assertEquals(0, terminal.general.executions);
    }
    public void testMissingDefaultConfirmationUsesTheChosenProfile() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        AgentProfiles.getInstance(getProject()).getState().defaultId = "";
        sessions.dispose();
        sessions = new AgentSessions(getProject(), now::get, choices -> choices.getFirst());
        ServiceContainerUtil.replaceService(getProject(), AgentSessions.class, sessions, getTestRootDisposable());
        sessions.insert(myFixture.getEditor()); var owned = terminal.created.getFirst();
        waitFor("chosen default launches", () -> owned.executions == 1); advance(3);
        waitFor("chosen default inserts", () -> owned.sends == 1);
        assertEquals(profile.id, AgentProfiles.getInstance(getProject()).getState().defaultId);
    }
    public void testBlockedProbeCannotExtendDeadlineOrSendLate() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal);
        var blocked = new java.util.concurrent.CountDownLatch(1); owned.blockedProbe = blocked;
        try {
            int probes = owned.probes.get(); sessions.insert(myFixture.getEditor());
            waitFor("blocked lookup", () -> owned.probes.get() > probes);
            advance(29); sessions.insert(myFixture.getEditor()); advance(2); sessions.insert(myFixture.getEditor());
            assertTrue(terminal.watchers.isEmpty()); blocked.countDown();
            assertEquals(0, owned.sends); assertEquals(1, terminal.created.size());
        } finally { blocked.countDown(); }
    }
    public void testProjectSessionDisposalDropsQueuedStartupReference() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference");
        sessions.insert(myFixture.getEditor()); var owned = terminal.created.getFirst();
        waitFor("initial launch", () -> owned.executions == 1);
        sessions.dispose(); advance(3);
        assertTrue(terminal.watchers.isEmpty()); assertEquals(0, owned.sends);
    }
    public void testDeletedReferenceDoesNotDiscardRunningTabOwnership() throws Exception {
        var terminal = setupTerminal(); openDiskFile("reference"); var owned = launch(terminal);
        var blocked = new java.util.concurrent.CountDownLatch(1); owned.blockedProbe = blocked;
        try {
            int probes = owned.probes.get(); sessions.insert(myFixture.getEditor());
            waitFor("reference captured", () -> owned.probes.get() > probes);
            Files.deleteIfExists(Path.of(myFixture.getFile().getVirtualFile().getPath())); blocked.countDown();
            waitFor("validation failure", () -> terminal.watchers.isEmpty()); assertEquals(0, owned.sends);
            owned.blockedProbe = null; openDiskFile("another file"); sessions.insert(myFixture.getEditor());
            waitFor("reuse after validation error", () -> owned.sends == 1);
            assertEquals(1, terminal.created.size()); assertEquals(1, owned.executions);
        } finally { blocked.countDown(); }
    }
    private void openDiskFile(String text) throws Exception {
        Path path = Files.createTempFile("snakeorcamel-agent-", ".txt").toRealPath(); Files.writeString(path,text);
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(getTestRootDisposable(),path.getParent().toString());
        Disposer.register(getTestRootDisposable(), () -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path); assertNotNull(file);
        myFixture.configureFromExistingVirtualFile(file);
    }
    private static final class FakeTerminal implements TerminalAccess {
        final FakeSession general = new FakeSession();
        final List<FakeSession> created = new ArrayList<>();
        final Map<FakeSession, Runnable> watchers = new IdentityHashMap<>();
        FakeSession front = general;
        boolean delayShow, bufferInitialCommand; int shows; Runnable shown;
        java.util.function.Consumer<FakeSession> configure = s -> {};
        @Override public Startup createForStartup(Path directory, String name, String command) {
            var session = new FakeSession(); session.directory = directory; session.name = name;
            session.onFocus = () -> front = session;
            configure.accept(session); created.add(session);
            if (bufferInitialCommand) session.execute(command);
            return new Startup(session, bufferInitialCommand);
        }
        @Override public void show(Runnable ready) { shows++; if (delayShow) shown = ready; else ready.run(); }
        void finishShow() { var callback = shown; shown = null; callback.run(); }
        @Override public AutoCloseable watch(Session session, Runnable invalidated) {
            var target = (FakeSession) session; watchers.put(target, invalidated); return () -> watchers.remove(target);
        }
        void move(FakeSession target) { front = general; assertTrue(target.alive); }
        void close(FakeSession target) { target.alive = false; var callback = watchers.get(target); if (callback != null) callback.run(); }
    }
    private static final class FakeSession implements TerminalAccess.Session {
        final List<String> input = new ArrayList<>(); Object connection = new Object();
        final java.util.concurrent.atomic.AtomicInteger probes = new java.util.concurrent.atomic.AtomicInteger();
        volatile java.util.concurrent.CountDownLatch blockedProbe;
        boolean shell = true, unknown, failProbe, rejectScreen, typedShell, failSend, failExecute;
        boolean connected = true, exitAfterLaunch, alive = true;
        int executions, sends; String command, name; Path directory; Runnable onFocus = () -> {};
        @Override public Object identity() { return this; }
        @Override public void refreshProcessState() {
            var application = com.intellij.openapi.application.ApplicationManager.getApplication();
            assertFalse(application.isDispatchThread()); assertFalse(application.isReadAccessAllowed());
            var blocked = blockedProbe; probes.incrementAndGet();
            if (failProbe) throw new IllegalStateException("Process lookup failed");
            if (blocked != null) {
                try { assertTrue(blocked.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
        }
        @Override public TerminalAccess.Connection connection() {
            return new TerminalAccess.Connection(connection, connected, unknown ? TerminalAccess.ProcessStatus.UNKNOWN
                    : shell ? TerminalAccess.ProcessStatus.IDLE : TerminalAccess.ProcessStatus.RUNNING);
        }
        @Override public TerminalAccess.Screen screen() {
            if (rejectScreen) throw new AssertionError("Running/unknown insertion must not read terminal text");
            com.intellij.openapi.application.ApplicationManager.getApplication().assertIsDispatchThread();
            return new TerminalAccess.Screen("existing question", "existing question", "", 0, connected,
                    shell && !typedShell, !shell, connection);
        }
        @Override public Path directory() { return directory; }
        @Override public void execute(String value) {
            executions++; command = value;
            if (failExecute) throw new IllegalStateException("execute failed");
            shell = exitAfterLaunch;
        }
        @Override public void insert(String text) { sends++; if (failSend) throw new IllegalStateException("send failed"); input.add(text); }
        @Override public void focus() { onFocus.run(); }
        @Override public boolean exists() { return alive; }
    }
    private record Marker(com.intellij.openapi.editor.RangeMarker range) implements AutoCloseable {
        @Override public void close() { range.dispose(); }
    }
}
