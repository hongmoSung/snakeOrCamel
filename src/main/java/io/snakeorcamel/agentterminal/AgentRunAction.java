package io.snakeorcamel.agentterminal;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
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
        int choice = Messages.showChooseDialog(project,"Choose a profile. Its own tab will be reused.","Run Agent",null,
                profiles.stream().map(p -> p.name).toArray(String[]::new),profiles.getFirst().name);
        if (choice >= 0) project.getService(AgentSessions.class).run(profiles.get(choice));
    }
}
