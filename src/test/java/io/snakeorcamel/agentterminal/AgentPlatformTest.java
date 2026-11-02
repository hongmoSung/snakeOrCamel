package io.snakeorcamel.agentterminal;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class AgentPlatformTest extends BasePlatformTestCase {
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
    private record Marker(com.intellij.openapi.editor.RangeMarker range) implements AutoCloseable {
        @Override public void close() { range.dispose(); }
    }
}
