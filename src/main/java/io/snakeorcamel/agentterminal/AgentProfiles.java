package io.snakeorcamel.agentterminal;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import java.util.*;

@State(name = "SnakeOrCamelAgentProfiles", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class AgentProfiles implements PersistentStateComponent<AgentProfiles.State> {
    public static final class State {
        public List<AgentProfile> profiles = new ArrayList<>(List.of(
                new AgentProfile("Claude", AgentProfile.Kind.Claude, "claude"),
                new AgentProfile("Codex", AgentProfile.Kind.Codex, "codex")));
        public String defaultId = "";
    }
    private State state = new State();
    public static AgentProfiles getInstance(Project p) { return p.getService(AgentProfiles.class); }
    @Override public @NotNull State getState() { return state; }
    @Override public void loadState(@NotNull State state) { this.state = state; }
    public List<AgentProfile> profiles() { return state.profiles.stream().map(AgentProfile::copy).toList(); }
    public AgentProfile defaultProfile() {
        return state.profiles.stream().filter(p -> p.id.equals(state.defaultId)).findFirst().map(AgentProfile::copy).orElse(null);
    }
}
