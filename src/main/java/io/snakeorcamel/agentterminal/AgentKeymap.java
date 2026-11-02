package io.snakeorcamel.agentterminal;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.keymap.*;
import com.intellij.openapi.util.SystemInfo;
import javax.swing.KeyStroke;
import java.util.*;

/** Owns only automatically added bindings. User edits relinquish ownership. */
public final class AgentKeymap implements Disposable {
    private boolean changing;
    public AgentKeymap() {
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(KeymapManagerListener.TOPIC, new KeymapManagerListener() {
            @Override public void activeKeymapChanged(Keymap keymap) { reconcile(); }
            @Override public void shortcutsChanged(Keymap keymap, Collection<String> actionIds, boolean fromSettings) {
                for (String id : actionIds) edited(keymap,id);
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
    private void edited(Keymap keymap, String id) {
        if (changing) return;
        if (id.equals(AgentSettings.INSERT_ACTION)) AgentSettings.getInstance().getState().ownedKeymaps.remove(keymap.getName());
        // A new conflicting assignment must not leave our automatic binding active.
        if (!conflicts(keymap).isEmpty()) removeOwned(keymap);
    }
    public void reconcile() {
        if (changing) return;
        changing = true;
        try {
            var settings = AgentSettings.getInstance();
            var manager = KeymapManager.getInstance();
            for (String name : List.copyOf(settings.getState().ownedKeymaps)) {
                Keymap keymap = manager.getKeymap(name);
                if (keymap == null) { settings.getState().ownedKeymaps.remove(name); continue; }
                if (!settings.isEnabled() || keymap != manager.getActiveKeymap() || !conflicts(keymap).isEmpty()) removeOwned(keymap);
            }
            Keymap active = manager.getActiveKeymap();
            if (settings.isEnabled() && conflicts(active).isEmpty()
                    && Arrays.stream(active.getShortcuts(AgentSettings.INSERT_ACTION)).noneMatch(shortcut()::equals)) {
                active.addShortcut(AgentSettings.INSERT_ACTION, shortcut());
                settings.getState().ownedKeymaps.add(active.getName());
            }
            if (!settings.isEnabled()) ApplicationManager.getApplication().getMessageBus().syncPublisher(AgentSettings.DISABLED).run();
        } finally { changing = false; }
    }
    private void removeOwned(Keymap keymap) {
        var owned = AgentSettings.getInstance().getState().ownedKeymaps;
        if (!owned.remove(keymap.getName())) return;
        boolean previous = changing; changing = true;
        try { keymap.removeShortcut(AgentSettings.INSERT_ACTION, shortcut()); }
        finally { changing = previous; }
    }
    public String status() {
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        List<String> ids = conflicts(keymap);
        if (!ids.isEmpty()) return "Shortcut conflict: " + String.join(", ", ids) + ". Existing assignments are preserved; use the menu or edit Keymap.";
        return "No conflicting assignment in " + keymap.getName() + ". The shortcut connects after enabling and applying.";
    }
    @Override public void dispose() { /* Persist ownership for the next IDE session; do not undo user edits. */ }
}
