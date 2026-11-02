package io.snakeorcamel.caseconvert;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CaseConvertAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean enabled = editor != null && editor.getCaretModel().getAllCarets().stream()
                .anyMatch(Caret::hasSelection);
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }
        Project project = e.getData(CommonDataKeys.PROJECT);
        List<Caret> carets = editor.getCaretModel().getAllCarets();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            for (Caret caret : carets) {
                String selected = caret.getSelectedText();
                if (selected == null || selected.isBlank()) {
                    continue;
                }
                String converted = convert(selected);
                editor.getDocument().replaceString(caret.getSelectionStart(), caret.getSelectionEnd(), converted);
            }
        });
    }

    private static String convert(String text) {
        return hasSeparator(text) ? ConvertUtil.toCamelCase(text) : ConvertUtil.toSnakeCase(text);
    }

    private static boolean hasSeparator(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '-') {
                return true;
            }
        }
        return false;
    }
}
