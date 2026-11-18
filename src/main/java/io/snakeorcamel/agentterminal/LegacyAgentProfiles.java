package io.snakeorcamel.agentterminal;

import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Reads the launch profiles saved by older versions in this project's workspace. */
@State(name = "SnakeOrCamelAgentProfiles", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class LegacyAgentProfiles implements PersistentStateComponent<AgentProfiles.State> {
    private AgentProfiles.State previous;

    @Override public @Nullable AgentProfiles.State getState() { return previous; }
    @Override public void loadState(@NotNull AgentProfiles.State state) { previous = state; }
}
