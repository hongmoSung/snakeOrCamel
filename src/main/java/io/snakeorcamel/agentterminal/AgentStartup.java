package io.snakeorcamel.agentterminal;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

public final class AgentStartup implements StartupActivity, DumbAware {
    @Override public void runActivity(@NotNull Project project) {
        // No terminal service is created here, including when enabled.
        ApplicationManager.getApplication().invokeLater(() -> AgentKeymap.getInstance().reconcile());
    }
}
