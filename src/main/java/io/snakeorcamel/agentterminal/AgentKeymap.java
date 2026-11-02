package io.snakeorcamel.agentterminal;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.*;
import com.intellij.openapi.keymap.ex.KeymapManagerEx;
import com.intellij.openapi.util.SystemInfo;
import javax.swing.KeyStroke;
import java.util.*;

/** Persistent, reversible shortcut changes. Only explicit enable or a keymap switch acquires ownership. */
public final class AgentKeymap implements Disposable {
    private boolean changing;
    public AgentKeymap() {
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(KeymapManagerListener.TOPIC, new KeymapManagerListener() {
            @Override public void activeKeymapChanged(Keymap keymap) { reconcile(); }
            @Override public void shortcutsChanged(Keymap keymap, Collection<String> actionIds, boolean fromSettings) {
                if (!changing) {
                    validateOwnership(keymap);
                    // A manual conflicting assignment takes precedence over our automatic binding.
                    if (!conflicts(keymap).isEmpty()) {
                        changing = true;
                        try {
                            for (var change : state().keymapChanges) {
                                if (change.name.equals(keymap.getName()) && !change.addedShortcut.isEmpty()) {
                                    keymap.removeShortcut(AgentSettings.INSERT_ACTION,
                                            new KeyboardShortcut(KeyStroke.getKeyStroke(change.addedShortcut), null));
                                    change.addedShortcut = "";
                                }
                            }
                        } finally { changing = false; }
                    }
                }
            }
        });
    }
    public static AgentKeymap getInstance() { return ApplicationManager.getApplication().getService(AgentKeymap.class); }
    public static KeyboardShortcut shortcut() {
        return new KeyboardShortcut(KeyStroke.getKeyStroke(SystemInfo.isMac ? "alt meta K" : "control alt K"), null);
    }
    public static List<String> conflicts(Keymap keymap) {
        return Arrays.stream(keymap.getActionIds(shortcut().getFirstKeyStroke()))
                .filter(id -> !id.equals(AgentSettings.INSERT_ACTION)).sorted().toList();
    }
    private static List<String> snapshot(Keymap keymap, String id) {
        return Arrays.stream(keymap.getShortcuts(id)).map(Object::toString).sorted().toList();
    }
    private static boolean contains(Keymap keymap, String id, Shortcut shortcut) {
        return Arrays.asList(keymap.getShortcuts(id)).contains(shortcut);
    }
    private AgentSettings.State state() { return AgentSettings.getInstance().getState(); }

    public void applyEnabled(boolean enabled) {
        boolean enableTransition = enabled && !state().enabled;
        state().enabled = enabled;
        reconcile(enableTransition);
    }
    public void reconcile() { reconcile(false); }
    private void reconcile(boolean enableTransition) {
        if (changing) return;
        changing = true;
        try {
            var manager = KeymapManager.getInstance();
            Keymap active = manager.getActiveKeymap();
            // Migrate ownership from versions which never displaced existing assignments.
            for (String name : state().ownedKeymaps) {
                var change = new AgentSettings.KeymapChange();
                change.name = name; change.addedShortcut = shortcut().getFirstKeyStroke().toString();
                state().keymapChanges.add(change);
            }
            state().ownedKeymaps.clear();
            boolean switched = !state().activeKeymap.isEmpty() && !state().activeKeymap.equals(active.getName());
            for (var change : List.copyOf(state().keymapChanges)) {
                Keymap keymap = manager.getKeymap(change.name);
                if (keymap != null) validateOwnership(keymap);
                if (!state().enabled || !change.name.equals(active.getName())) {
                    if (keymap != null) restore(keymap, change);
                    state().keymapChanges.remove(change);
                }
            }
            if (state().enabled && (enableTransition || switched)) active = install(active);
            state().activeKeymap = active.getName();
            if (!state().enabled) ApplicationManager.getApplication().getMessageBus().syncPublisher(AgentSettings.DISABLED).run();
        } finally { changing = false; }
    }
    private Keymap install(Keymap keymap) {
        if (!keymap.canModify()) {
            // Bundled schemes are not persisted by the IDE. Use a derived, writable scheme.
            var manager = KeymapManagerEx.getInstanceEx();
            String base = keymap.getName() + " (Agent Terminal)";
            String name = base;
            for (int i = 2; manager.getKeymap(name) != null; i++) name = base + " " + i;
            keymap = keymap.deriveKeymap(name);
            manager.getSchemeManager().addScheme(keymap);
            manager.setActiveKeymap(keymap);
        }
        var change = new AgentSettings.KeymapChange();
        change.name = keymap.getName();
        state().keymapChanges.add(change);
        for (String id : conflicts(keymap)) {
            for (Shortcut binding : keymap.getShortcuts(id)) {
                if (binding instanceof KeyboardShortcut keyboard && keyboard.getFirstKeyStroke().equals(shortcut().getFirstKeyStroke())) {
                    var removed = new AgentSettings.RemovedBinding();
                    removed.actionId = id;
                    removed.first = keyboard.getFirstKeyStroke().toString();
                    removed.second = keyboard.getSecondKeyStroke() == null ? "" : keyboard.getSecondKeyStroke().toString();
                    change.removed.add(removed);
                    keymap.removeShortcut(id, binding);
                }
            }
        }
        for (var removed : change.removed) removed.expected = new ArrayList<>(snapshot(keymap, removed.actionId));
        if (!contains(keymap, AgentSettings.INSERT_ACTION, shortcut())) {
            change.addedShortcut = shortcut().getFirstKeyStroke().toString();
            keymap.addShortcut(AgentSettings.INSERT_ACTION, shortcut());
        }
        return keymap;
    }
    private void validateOwnership(Keymap keymap) {
        for (var change : state().keymapChanges) {
            if (!change.name.equals(keymap.getName())) continue;
            if (!change.addedShortcut.isEmpty() && !contains(keymap, AgentSettings.INSERT_ACTION,
                    new KeyboardShortcut(KeyStroke.getKeyStroke(change.addedShortcut), null))) change.addedShortcut = "";
            // If an action was edited while we were active (or while the IDE was closed), leave it alone.
            change.removed.removeIf(binding -> !binding.expected.equals(snapshot(keymap, binding.actionId)));
        }
    }
    private void restore(Keymap keymap, AgentSettings.KeymapChange change) {
        if (!change.addedShortcut.isEmpty()) keymap.removeShortcut(AgentSettings.INSERT_ACTION,
                new KeyboardShortcut(KeyStroke.getKeyStroke(change.addedShortcut), null));
        for (var binding : change.removed) keymap.addShortcut(binding.actionId,
                new KeyboardShortcut(KeyStroke.getKeyStroke(binding.first),
                        binding.second.isEmpty() ? null : KeyStroke.getKeyStroke(binding.second)));
    }
    public String status() {
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        String bindings = Arrays.stream(keymap.getShortcuts(AgentSettings.INSERT_ACTION))
                .map(KeymapUtil::getShortcutText).collect(java.util.stream.Collectors.joining(", "));
        List<String> displaced = state().keymapChanges.stream().filter(c -> c.name.equals(keymap.getName()))
                .flatMap(c -> c.removed.stream()).map(b -> b.actionId).distinct().sorted().toList();
        return "Keymap: " + keymap.getPresentableName() + ". Registered shortcuts: " + (bindings.isEmpty() ? "none" : bindings)
                + ". Replaced assignments: " + (displaced.isEmpty() ? "none" : String.join(", ", displaced))
                + ". Applying OFF restores unchanged assignments. Applying OFF then ON assigns "
                + KeymapUtil.getShortcutText(shortcut()) + " with priority.";
    }
    @Override public void dispose() { /* Keep the journal for restoration after restart. */ }
}
