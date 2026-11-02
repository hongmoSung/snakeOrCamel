package io.snakeorcamel.agentterminal;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

public final class AgentInsertReferenceAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        var project = e.getProject(); var editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null || !AgentRunAction.enabled(e)) return;
        project.getService(AgentSessions.class).insert(editor);
    }
}
