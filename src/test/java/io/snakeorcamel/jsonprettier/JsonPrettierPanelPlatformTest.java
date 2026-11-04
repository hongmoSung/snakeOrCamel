package io.snakeorcamel.jsonprettier;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.List;

/** Real Swing button events, deliberately without the fixture's implicit EDT lock. */
public class JsonPrettierPanelPlatformTest extends BasePlatformTestCase {
    private JsonPrettierPanel panel;
    private Editor editor;

    @Override protected boolean runInDispatchThread() { return false; }
    @Override protected boolean isWriteActionRequired() { return false; }

    @Override protected void setUp() throws Exception {
        super.setUp();
        ApplicationManager.getApplication().invokeAndWait(() -> WriteIntentReadAction.run(() -> {
            panel = new JsonPrettierPanel(getProject());
            Disposer.register(getTestRootDisposable(), panel);
            for (Editor candidate : EditorFactory.getInstance().getAllEditors()) {
                if (panel.isAncestorOf(candidate.getComponent())) editor = candidate;
            }
            assertNotNull(editor);
        }));
    }

    public void testFormatAndFoldButtonsFromUnlockedSwingEvent() throws Exception {
        String input = "[{\"name\":\"first\",\"values\":[1,2]},{\"name\":\"second\"}]";
        setText(input);
        click("Format");
        ApplicationManager.getApplication().invokeAndWait(() -> WriteIntentReadAction.run(() -> {
            assertEquals(JsonUtil.prettify(input), editor.getDocument().getText());
            assertTrue(PsiDocumentManager.getInstance(getProject()).isCommitted(editor.getDocument()));
            assertTrue(labels().contains("Array: 2 items"));
            assertTrue(editor.getFoldingModel().getAllFoldRegions().length > 0);
        }));
        click("Collapse All");
        assertFoldsExpanded(false);
        click("Expand All");
        assertFoldsExpanded(true);
    }

    public void testInvalidJsonPreservesInputAndShowsError() throws Exception {
        String input = "{\"name\":";
        setText(input);
        click("Format");
        ApplicationManager.getApplication().invokeAndWait(() -> {
            assertEquals(input, editor.getDocument().getText());
            assertTrue(labels().stream().anyMatch(s -> s.startsWith("Invalid JSON:")));
        });
    }

    public void testBlankInputClearsPreviousError() throws Exception {
        setText("{\"name\":");
        click("Format");
        setText("   ");
        click("Format");
        ApplicationManager.getApplication().invokeAndWait(() -> {
            assertEquals("   ", editor.getDocument().getText());
            assertFalse(labels().stream().anyMatch(s -> s.startsWith("Invalid JSON:") || s.startsWith("Array:")));
        });
    }

    public void testObjectFormattingClearsPreviousArrayCount() throws Exception {
        setText("[1,2]");
        click("Format");
        ApplicationManager.getApplication().invokeAndWait(() -> assertTrue(labels().contains("Array: 2 items")));
        String input = "{\"nested\":{\"value\":true}}";
        setText(input);
        click("Format");
        ApplicationManager.getApplication().invokeAndWait(() -> {
            assertEquals(JsonUtil.prettify(input), editor.getDocument().getText());
            assertFalse(labels().stream().anyMatch(s -> s.startsWith("Array:")));
        });
    }

    public void testFormattingIsOneUndoableCommand() throws Exception {
        String input = "{\"values\":[1,2]}";
        setText(input);
        click("Format");
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var fileEditor = TextEditorProvider.getInstance().getTextEditor(editor);
            var undo = UndoManager.getInstance(getProject());
            assertEquals(JsonUtil.prettify(input), editor.getDocument().getText());
            assertTrue(undo.isUndoAvailable(fileEditor));
            undo.undo(fileEditor);
            assertEquals(input, editor.getDocument().getText());
            assertTrue(undo.isRedoAvailable(fileEditor));
            undo.redo(fileEditor);
            assertEquals(JsonUtil.prettify(input), editor.getDocument().getText());
        });
    }

    public void testCopyUsesEntireFormattedDocumentAndPreservesInput() throws Exception {
        String input = "[{\"value\":1},{\"value\":2}]";
        setText(input);
        click("Format");
        Transferable[] previous = new Transferable[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            previous[0] = CopyPasteManager.getInstance().getContents();
            editor.getSelectionModel().setSelection(0, 1);
        });
        try {
            click("Copy");
            ApplicationManager.getApplication().invokeAndWait(() -> {
                assertEquals(JsonUtil.prettify(input), CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));
                assertEquals(JsonUtil.prettify(input), editor.getDocument().getText());
            });
        } finally {
            ApplicationManager.getApplication().invokeAndWait(() -> CopyPasteManager.getInstance()
                    .setContents(previous[0] == null ? new StringSelection("") : previous[0]));
        }
    }

    public void testQueuedFormatAndFoldingAreCancelledWhenPanelCloses() throws Exception {
        String input = "{\"values\":[1,2]}";
        setText(input);
        var document = editor.getDocument();
        SwingUtilities.invokeAndWait(() -> {
            assertUnlockedEvent();
            button("Format").doClick(0);
            button("Collapse All").doClick(0);
            button("Expand All").doClick(0);
            // Dispose before any of the queued callbacks can run.
            WriteIntentReadAction.run(() -> Disposer.dispose(panel));
        });
        ApplicationManager.getApplication().invokeAndWait(() -> {
            assertTrue(editor.isDisposed());
            assertEquals(input, document.getText());
        });
    }

    public void testRepeatedFormatDoesNotDuplicateFolds() throws Exception {
        String input = "{\"nested\":{\"values\":[1,2,3]}}";
        setText(input);
        click("Format");
        int[] folds = new int[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            folds[0] = editor.getFoldingModel().getAllFoldRegions().length;
            assertTrue(folds[0] > 0);
        });
        click("Format");
        click("Format");
        ApplicationManager.getApplication().invokeAndWait(() -> {
            assertEquals(JsonUtil.prettify(input), editor.getDocument().getText());
            assertEquals(folds[0], editor.getFoldingModel().getAllFoldRegions().length);
        });
    }

    public void testFoldButtonsBeforeFormattingPreserveInput() throws Exception {
        String input = "{\"value\":1}";
        setText(input);
        click("Collapse All");
        click("Expand All");
        ApplicationManager.getApplication().invokeAndWait(() -> assertEquals(input, editor.getDocument().getText()));
    }

    private void setText(String text) {
        ApplicationManager.getApplication().invokeAndWait(() ->
                WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().setText(text)));
    }

    private void click(String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertUnlockedEvent();
            button(text).doClick(0);
        });
    }

    private void assertUnlockedEvent() {
        assertTrue(SwingUtilities.isEventDispatchThread());
        assertFalse("Regression requires an EDT event without write intent",
                ApplicationManager.getApplication().isWriteIntentLockAcquired());
        assertFalse(ApplicationManager.getApplication().isWriteAccessAllowed());
    }

    private JButton button(String text) {
        return components(panel).stream().filter(JButton.class::isInstance)
                .map(JButton.class::cast).filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }

    private void assertFoldsExpanded(boolean expanded) {
        // The folding buttons enqueue work with Application.invokeLater; drain that work first.
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var folds = editor.getFoldingModel().getAllFoldRegions();
            assertTrue(folds.length > 0);
            for (var fold : folds) assertEquals(expanded, fold.isExpanded());
        });
    }

    private List<String> labels() {
        return components(panel).stream().filter(JLabel.class::isInstance)
                .map(JLabel.class::cast).map(JLabel::getText).filter(java.util.Objects::nonNull).toList();
    }

    private static List<Component> components(Container parent) {
        var result = new ArrayList<Component>();
        for (Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container container) result.addAll(components(container));
        }
        return result;
    }
}
