package io.snakeorcamel.agentterminal;

import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.ex.KeymapManagerEx;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import javax.swing.KeyStroke;
import java.util.*;

public class AgentKeymapPlatformTest extends BasePlatformTestCase {
    private Keymap original;
    private final List<Keymap> created = new ArrayList<>();
    private AgentSettings.State saved;
    private static final String CONFLICT = "SnakeOrCamel.TestConflict";
    private static final KeyboardShortcut OTHER = new KeyboardShortcut(KeyStroke.getKeyStroke("ctrl alt shift F12"), null);
    private AgentKeymap service() { return AgentKeymap.getInstance(); }
    private KeymapManagerEx manager() { return KeymapManagerEx.getInstanceEx(); }
    @Override protected void setUp() throws Exception {
        super.setUp();
        saved = AgentSettings.getInstance().getState();
        AgentSettings.getInstance().loadState(new AgentSettings.State());
        original = manager().getActiveKeymap();
        service();
        manager().setActiveKeymap(createKeymap());
    }
    private Keymap createKeymap() {
        Keymap keymap = original.deriveKeymap("Agent test " + UUID.randomUUID());
        manager().getSchemeManager().addScheme(keymap); created.add(keymap);
        // Remove inherited conflicts to make each scenario independent of the host OS keymap.
        for (String id : keymap.getActionIds(AgentKeymap.shortcut().getFirstKeyStroke()))
            for (var shortcut : keymap.getShortcuts(id))
                if (shortcut instanceof KeyboardShortcut k && k.getFirstKeyStroke().equals(AgentKeymap.shortcut().getFirstKeyStroke()))
                    keymap.removeShortcut(id, shortcut);
        return keymap;
    }
    @Override protected void tearDown() throws Exception {
        try {
            service().applyEnabled(false);
            manager().setActiveKeymap(original);
            for (Keymap keymap : created) manager().getSchemeManager().removeScheme(keymap);
            AgentSettings.getInstance().loadState(saved);
        } finally { super.tearDown(); }
    }
    private boolean has(Keymap keymap, String id, KeyboardShortcut shortcut) {
        return Arrays.asList(keymap.getShortcuts(id)).contains(shortcut);
    }
    public void testEnableDisableAndRepeatedApply() {
        Keymap keymap = manager().getActiveKeymap();
        service().applyEnabled(true); service().applyEnabled(true); service().reconcile();
        assertTrue(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
        assertEquals(1, AgentSettings.getInstance().getState().keymapChanges.size());
        service().applyEnabled(false);
        assertFalse(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
        service().applyEnabled(true);
        assertTrue(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
    }
    public void testConflictChordAndOtherShortcutsSurviveRestartAndRestore() {
        Keymap keymap = manager().getActiveKeymap();
        var chord = new KeyboardShortcut(AgentKeymap.shortcut().getFirstKeyStroke(), KeyStroke.getKeyStroke("C"));
        keymap.addShortcut(CONFLICT, AgentKeymap.shortcut()); keymap.addShortcut(CONFLICT, chord); keymap.addShortcut(CONFLICT, OTHER);
        service().applyEnabled(true);
        assertFalse(has(keymap, CONFLICT, chord)); assertFalse(has(keymap, CONFLICT, AgentKeymap.shortcut()));
        assertTrue(has(keymap, CONFLICT, OTHER));
        assertTrue(service().status().contains(CONFLICT));
        var restored = XmlSerializer.deserialize(XmlSerializer.serialize(AgentSettings.getInstance().getState()), AgentSettings.State.class);
        AgentSettings.getInstance().loadState(restored);
        service().reconcile(); service().applyEnabled(true);
        assertEquals(2, restored.keymapChanges.getFirst().removed.size());
        service().applyEnabled(false);
        assertTrue(has(keymap, CONFLICT, chord)); assertTrue(has(keymap, CONFLICT, AgentKeymap.shortcut()));
        assertTrue(has(keymap, CONFLICT, OTHER));
        assertFalse(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
    }
    public void testManualRemovalSurvivesReconcileAndRepeatedApplyButExplicitEnableReclaims() {
        Keymap keymap = manager().getActiveKeymap(); service().applyEnabled(true);
        keymap.removeShortcut(AgentSettings.INSERT_ACTION, AgentKeymap.shortcut());
        keymap.addShortcut(AgentSettings.INSERT_ACTION, OTHER);
        keymap.addShortcut(CONFLICT, AgentKeymap.shortcut());
        service().reconcile(); service().applyEnabled(true);
        assertFalse(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
        service().applyEnabled(false);
        assertTrue(has(keymap, CONFLICT, AgentKeymap.shortcut()));
        assertTrue(has(keymap, AgentSettings.INSERT_ACTION, OTHER));
        service().applyEnabled(true);
        assertTrue(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
        assertFalse(has(keymap, CONFLICT, AgentKeymap.shortcut()));
    }
    public void testUserEditedDisplacedActionIsNotOverwritten() {
        Keymap keymap = manager().getActiveKeymap(); keymap.addShortcut(CONFLICT, AgentKeymap.shortcut());
        service().applyEnabled(true); keymap.addShortcut(CONFLICT, OTHER);
        service().applyEnabled(false);
        assertFalse(has(keymap, CONFLICT, AgentKeymap.shortcut())); assertTrue(has(keymap, CONFLICT, OTHER));
    }
    public void testSwitchRestoresPreviousAndRegistersNewKeymap() {
        Keymap first = manager().getActiveKeymap(); Keymap second = createKeymap();
        first.addShortcut(CONFLICT, AgentKeymap.shortcut()); second.addShortcut(CONFLICT, AgentKeymap.shortcut());
        service().applyEnabled(true); manager().setActiveKeymap(second);
        assertTrue(has(first, CONFLICT, AgentKeymap.shortcut()));
        assertFalse(has(first, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
        assertTrue(has(second, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
        assertFalse(has(second, CONFLICT, AgentKeymap.shortcut()));
        manager().setActiveKeymap(first);
        assertTrue(has(second, CONFLICT, AgentKeymap.shortcut()));
        assertTrue(has(first, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
    }
    public void testManualConflictTakesPrecedenceWithoutReassignment() {
        Keymap keymap = manager().getActiveKeymap(); service().applyEnabled(true);
        keymap.addShortcut(CONFLICT, AgentKeymap.shortcut());
        assertFalse(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
        service().reconcile();
        assertTrue(has(keymap, CONFLICT, AgentKeymap.shortcut()));
        assertFalse(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
    }
    public void testExistingUserAssignmentIsNotRemoved() {
        Keymap keymap = manager().getActiveKeymap(); keymap.addShortcut(AgentSettings.INSERT_ACTION, AgentKeymap.shortcut());
        service().applyEnabled(true); service().applyEnabled(false);
        assertTrue(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
    }
    public void testOpeningAndCancellingSettingsDoesNotChangeKeymap() {
        Keymap keymap = manager().getActiveKeymap(); keymap.addShortcut(CONFLICT, AgentKeymap.shortcut());
        var configurable = new AgentConfigurable(getProject());
        configurable.createComponent(); configurable.reset(); configurable.disposeUIResources();
        assertFalse(AgentSettings.getInstance().isEnabled());
        assertTrue(has(keymap, CONFLICT, AgentKeymap.shortcut()));
        assertFalse(has(keymap, AgentSettings.INSERT_ACTION, AgentKeymap.shortcut()));
    }
}
