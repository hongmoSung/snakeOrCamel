package io.snakeorcamel.jsonprettier;

import com.intellij.ide.highlighter.HighlighterFactory;
import com.intellij.json.JsonFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.codeInsight.folding.CodeFoldingManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.LocalTimeCounter;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;

public class JsonPrettierPanel extends JPanel implements Disposable {

    private static final String BUFFER_NAME = "snakeOrCamel-buffer.json";

    private final Project project;
    private final Editor editor;
    private final JBLabel statusLabel;

    public JsonPrettierPanel(@NotNull Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        statusLabel = new JBLabel("");
        JPanel header = new JPanel(new BorderLayout());
        header.add(new JBLabel("Enter JSON"), BorderLayout.WEST);
        header.add(statusLabel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        editor = createJsonEditor(project);
        add(editor.getComponent(), BorderLayout.CENTER);

        add(buildButtonBar(), BorderLayout.SOUTH);
    }

    private static Editor createJsonEditor(Project project) {
        FileType jsonType = JsonFileType.INSTANCE;
        PsiFile psiFile = PsiFileFactory.getInstance(project).createFileFromText(
                BUFFER_NAME, jsonType, "", LocalTimeCounter.currentTime(), true, false);
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) {
            document = EditorFactory.getInstance().createDocument("");
        }

        EditorEx editor = (EditorEx) EditorFactory.getInstance()
                .createEditor(document, project, jsonType, false);
        editor.setHighlighter(HighlighterFactory.createHighlighter(project, jsonType));
        editor.getSettings().setLineNumbersShown(true);
        editor.getSettings().setFoldingOutlineShown(true);
        editor.getSettings().setUseSoftWraps(false);
        editor.getSettings().setVirtualSpace(false);
        editor.getSettings().setAdditionalLinesCount(0);
        editor.getSettings().setAdditionalColumnsCount(0);
        return editor;
    }

    private JPanel buildButtonBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton format = new JButton("Format");
        JButton expandAll = new JButton("Expand All");
        JButton collapseAll = new JButton("Collapse All");
        JButton copy = new JButton("Copy");

        format.addActionListener(e -> formatJson());
        expandAll.addActionListener(e -> setAllFoldsExpanded(true));
        collapseAll.addActionListener(e -> setAllFoldsExpanded(false));
        copy.addActionListener(e -> copyToClipboard());

        bar.add(format);
        bar.add(expandAll);
        bar.add(collapseAll);
        bar.add(copy);
        return bar;
    }

    private void formatJson() {
        String input = editor.getDocument().getText();
        if (input.isBlank()) {
            clearStatus();
            return;
        }

        try {
            String formatted = JsonUtil.prettify(input);
            WriteCommandAction.runWriteCommandAction(project,
                    () -> editor.getDocument().setText(formatted));

            PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
            CodeFoldingManager.getInstance(project).updateFoldRegions(editor);

            int arrayCount = JsonUtil.getArrayCount(formatted);
            if (arrayCount >= 0) {
                showStatus("Array: " + arrayCount + " items", false);
            } else {
                clearStatus();
            }
        } catch (IllegalArgumentException e) {
            showStatus("Invalid JSON: " + e.getMessage(), true);
        }
    }

    private void setAllFoldsExpanded(boolean expanded) {
        ApplicationManager.getApplication().invokeLater(() -> {
            FoldingModel folding = editor.getFoldingModel();
            folding.runBatchFoldingOperation(() -> {
                for (FoldRegion region : folding.getAllFoldRegions()) {
                    region.setExpanded(expanded);
                }
            });
        });
    }

    private void copyToClipboard() {
        String text = editor.getDocument().getText();
        if (!text.isEmpty()) {
            CopyPasteManager.getInstance().setContents(new StringSelection(text));
        }
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.setForeground(error ? JBColor.RED : null);
    }

    private void clearStatus() {
        statusLabel.setText("");
        statusLabel.setForeground((Color) null);
    }

    @Override
    public void dispose() {
        if (!editor.isDisposed()) {
            EditorFactory.getInstance().releaseEditor(editor);
        }
    }
}
