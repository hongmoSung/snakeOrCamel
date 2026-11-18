package io.snakeorcamel.agentterminal;

import com.intellij.openapi.components.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import java.util.*;

@State(name = "SnakeOrCamelSharedAgentProfiles", storages = @Storage(value = "snakeOrCamelAgentProfiles.xml", roamingType = RoamingType.DISABLED))
public final class AgentProfiles implements PersistentStateComponent<AgentProfiles.State> {
    public static final class State {
        public List<AgentProfile> profiles = new ArrayList<>(List.of(
                new AgentProfile("Claude", AgentProfile.Kind.Claude, "claude"),
                new AgentProfile("Codex", AgentProfile.Kind.Codex, "codex")));
        public String defaultId = "";
        public boolean configured;
    }
    private State state = new State();
    public static AgentProfiles getInstance() { return ApplicationManager.getApplication().getService(AgentProfiles.class); }
    public static AgentProfiles getInstance(Project project) {
        AgentProfiles shared = getInstance();
        if (!shared.state.configured) {
            State previous = project.getService(LegacyAgentProfiles.class).getState();
            if (previous != null) shared.saveProfiles(previous.profiles, previous.defaultId);
        }
        return shared;
    }
    @Override public @NotNull State getState() { return state; }
    @Override public void loadState(@NotNull State state) { this.state = state; }
    public void saveProfiles(List<AgentProfile> profiles, String defaultId) {
        State updated = new State();
        updated.profiles = new ArrayList<>(profiles.stream().map(AgentProfile::copy).toList());
        updated.defaultId = defaultId;
        updated.configured = true;
        state = updated;
    }
    public List<AgentProfile> profiles() { return state.profiles.stream().map(AgentProfile::copy).toList(); }
    public AgentProfile defaultProfile() {
        return state.profiles.stream().filter(p -> p.id.equals(state.defaultId)).findFirst().map(AgentProfile::copy).orElse(null);
    }
}
