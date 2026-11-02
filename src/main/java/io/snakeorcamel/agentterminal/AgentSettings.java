package io.snakeorcamel.agentterminal;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;

/** Only explicit user consent enables terminal access. Never stored in a project. */
@State(name = "SnakeOrCamelAgentTerminal", storages = @Storage(value = "snakeOrCamelAgentTerminal.xml", roamingType = RoamingType.DISABLED))
public final class AgentSettings implements PersistentStateComponent<AgentSettings.State> {
    public static final com.intellij.util.messages.Topic<Runnable> DISABLED =
            new com.intellij.util.messages.Topic<>("SnakeOrCamel agent disabled", Runnable.class);
    public static final String INSERT_ACTION = "SnakeOrCamel.AgentTerminal.InsertReference";
    public static final String RUN_ACTION = "SnakeOrCamel.AgentTerminal.Run";
    public static final class State {
        public boolean enabled;
        public String activeKeymap = "";
        public java.util.List<KeymapChange> keymapChanges = new java.util.ArrayList<>();
        public java.util.List<String> ownedKeymaps = new java.util.ArrayList<>();
    }
    public static final class KeymapChange {
        public String name = "";
        public String addedShortcut = "";
        public java.util.List<RemovedBinding> removed = new java.util.ArrayList<>();
    }
    public static final class RemovedBinding {
        public String actionId = "";
        public String first = "";
        public String second = "";
        public java.util.List<String> expected = new java.util.ArrayList<>();
    }
    private State state = new State();
    public static AgentSettings getInstance() { return ApplicationManager.getApplication().getService(AgentSettings.class); }
    @Override public @NotNull State getState() { return state; }
    @Override public void loadState(@NotNull State state) { this.state = state; }
    public boolean isEnabled() { return state.enabled; }
}
