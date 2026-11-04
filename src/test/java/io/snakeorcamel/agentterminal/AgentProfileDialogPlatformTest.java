package io.snakeorcamel.agentterminal;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class AgentProfileDialogPlatformTest extends BasePlatformTestCase {
    public void testDefaultAndDuplicateNamesSelectActualProfile() {
        var first = new AgentProfile("same", AgentProfile.Kind.Claude, "claude");
        var second = new AgentProfile("same", AgentProfile.Kind.Codex, "codex");
        var dialog = new AgentProfileDialog(getProject(), List.of(first, second), "Run Agent", "Choose a profile.");
        try {
            assertSame(first, dialog.selectedProfile());
            ((ComboBox<?>) dialog.getPreferredFocusedComponent()).setSelectedIndex(1);
            assertSame(second, dialog.selectedProfile());
            dialog.close(DialogWrapper.OK_EXIT_CODE);
            assertTrue(dialog.isOK());
        } finally { Disposer.dispose(dialog.getDisposable()); }
    }

    public void testCancellationDoesNotChangeDefault() {
        var profiles = AgentProfiles.getInstance(getProject());
        String original = profiles.getState().defaultId;
        var choices = List.of(new AgentProfile("Claude", AgentProfile.Kind.Claude, "claude"),
                new AgentProfile("Codex", AgentProfile.Kind.Codex, "codex"));
        var dialog = new AgentProfileDialog(getProject(), choices, "Agent Terminal", "Choose a default.");
        try {
            ((ComboBox<?>) dialog.getPreferredFocusedComponent()).setSelectedIndex(1);
            dialog.doCancelAction();
            assertFalse(dialog.isOK());
            assertEquals(original, profiles.getState().defaultId);
        } finally { Disposer.dispose(dialog.getDisposable()); }
    }

    public void testEmptyChoicesCannotBeConfirmed() {
        var dialog = new AgentProfileDialog(getProject(), List.of(), "Run Agent", "Choose a profile.");
        try {
            assertNull(dialog.selectedProfile());
            assertFalse(dialog.isOKActionEnabled());
        } finally { Disposer.dispose(dialog.getDisposable()); }
    }
}
