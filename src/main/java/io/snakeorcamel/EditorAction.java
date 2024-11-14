package io.snakeorcamel;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Editor;

import java.util.List;
import java.util.Objects;

public class EditorAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {

        Editor editor = e.getData(PlatformDataKeys.EDITOR);
        if (null == editor) {
            return;
        }
        CaretModel caretModel = editor.getCaretModel();
        List<Caret> carets = caretModel.getAllCarets();

        Runnable action = () -> {
            for (Caret caret : carets) {
                String selectedText = caret.getSelectedText();
                if (Objects.requireNonNull(selectedText).isBlank()) {
                    continue;
                }
                String convertedText = selectedText.contains("_")
                        ? ConvertUtil.toCamelCase(selectedText) : ConvertUtil.toSnakeCase(selectedText);
                editor.getDocument().replaceString(caret.getSelectionStart(), caret.getSelectionEnd(), convertedText);
            }
        };

        WriteCommandAction.runWriteCommandAction(e.getData(PlatformDataKeys.PROJECT), action);
    }}
