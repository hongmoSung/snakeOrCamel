package io.snakeorcamel.agentterminal;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class AgentRunAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(e.getProject() != null); }
    static boolean enabled(AnActionEvent e) {
        if (AgentSettings.getInstance().isEnabled()) return true;
        AgentNotifications.show(e.getProject(),"Agent Terminal is off. Read the first-tab and shortcut guidance in Settings → Tools → SnakeOrCamel → Agent Terminal to enable it.",null,null);
        return false;
    }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject(); if (project == null || !enabled(e)) return;
        var profiles = AgentProfiles.getInstance(project).profiles();
        if (profiles.isEmpty()) { AgentNotifications.show(project,"Add a launch profile in Agent Terminal settings.",null,null); return; }
        var choice = AgentProfileDialog.choose(project, profiles, "Run Agent", "Choose a profile. Its own tab will be reused.");
        if (choice != null) project.getService(AgentSessions.class).run(choice);
    }
}
