package io.snakeorcamel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.FoldingModelEx;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

public class JsonPrettierPanel extends JPanel {

    private Editor editor;
    private final Project project;
    private final JPanel editorPanel;
    private final JBLabel statusLabel;

    public JsonPrettierPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(new JBLabel("Enter JSON"), BorderLayout.WEST);
        statusLabel = new JBLabel("");
        headerPanel.add(statusLabel, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        editorPanel = new JPanel(new BorderLayout());
        add(editorPanel, BorderLayout.CENTER);

        createEditor();

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton formatButton = new JButton("Format");
        JButton expandAllButton = new JButton("Expand All");
        JButton collapseAllButton = new JButton("Collapse All");
        JButton copyButton = new JButton("Copy");

        formatButton.addActionListener(e -> formatJson());
        expandAllButton.addActionListener(e -> expandAllFolds());
        collapseAllButton.addActionListener(e -> collapseAllFolds());
        copyButton.addActionListener(e -> copyResult());

        buttonPanel.add(formatButton);
        buttonPanel.add(expandAllButton);
        buttonPanel.add(collapseAllButton);
        buttonPanel.add(copyButton);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void createEditor() {
        if (editor != null) {
            EditorFactory.getInstance().releaseEditor(editor);
        }

        Document document = EditorFactory.getInstance().createDocument("");

        editor = EditorFactory.getInstance().createEditor(document, project, PlainTextFileType.INSTANCE, false);

        if (editor instanceof EditorEx editorEx) {
            EditorSettings settings = editorEx.getSettings();
            settings.setLineNumbersShown(true);
            settings.setFoldingOutlineShown(true);
            settings.setAutoCodeFoldingEnabled(false);
            settings.setUseSoftWraps(false);
            settings.setAdditionalLinesCount(0);
            settings.setAdditionalColumnsCount(0);
            settings.setVirtualSpace(false);
        }

        editorPanel.removeAll();
        editorPanel.add(editor.getComponent(), BorderLayout.CENTER);
        editorPanel.revalidate();
        editorPanel.repaint();
    }

    private void setupFolding(EditorEx editorEx, Document document, String content) {
        List<int[]> foldRanges = findBracketRanges(content);
        if (!foldRanges.isEmpty()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (editor != null && !editor.isDisposed()) {
                    FoldingModelEx foldingModel = editorEx.getFoldingModel();
                    foldingModel.runBatchFoldingOperation(() -> {
                        for (int[] range : foldRanges) {
                            int start = range[0];
                            int end = range[1];
                            char startChar = range[2] == 0 ? '{' : '[';

                            if (start < document.getTextLength() && end <= document.getTextLength()) {
                                String placeholder = startChar == '{' ? "{...}" : "[...]";
                                FoldRegion region = foldingModel.addFoldRegion(start, end, placeholder);
                                if (region != null) {
                                    region.setExpanded(true);
                                }
                            }
                        }
                    });
                }
            });
        }
    }

    private static final Color COLOR_KEY = new JBColor(new Color(0, 51, 179), new Color(86, 156, 214));
    private static final Color COLOR_STRING = new JBColor(new Color(6, 125, 23), new Color(152, 195, 121));
    private static final Color COLOR_NUMBER = new JBColor(new Color(174, 92, 0), new Color(209, 154, 102));
    private static final Color COLOR_BOOLEAN = new JBColor(new Color(170, 13, 145), new Color(198, 120, 221));
    private static final Color COLOR_NULL = new JBColor(Gray._128, Gray._128);
    private static final Color COLOR_BRACES = new JBColor(new Color(150, 110, 30), new Color(229, 192, 123));

    private void applyJsonHighlighting(EditorEx editor, String content) {
        MarkupModel markupModel = editor.getMarkupModel();

        int i = 0;

        while (i < content.length()) {
            char c = content.charAt(i);

            if (c == '{' || c == '[') {
                addHighlight(markupModel, i, i + 1, COLOR_BRACES, Font.BOLD);
                i++;
            } else if (c == '}' || c == ']') {
                addHighlight(markupModel, i, i + 1, COLOR_BRACES, Font.BOLD);
                i++;
            } else if (c == ':' || c == ',') {
                i++;
            } else if (c == '"') {
                // Find end of string
                int start = i;
                i++;
                while (i < content.length()) {
                    if (content.charAt(i) == '\\' && i + 1 < content.length()) {
                        i += 2; // Skip escaped character
                    } else if (content.charAt(i) == '"') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }

                int j = i;
                while (j < content.length() && Character.isWhitespace(content.charAt(j))) {
                    j++;
                }
                boolean isKey = j < content.length() && content.charAt(j) == ':';
                addHighlight(markupModel, start, i, isKey ? COLOR_KEY : COLOR_STRING, isKey ? Font.BOLD : Font.PLAIN);
            } else if (Character.isDigit(c) || c == '-') {

                int start = i;
                if (c == '-') i++;
                while (i < content.length() && (Character.isDigit(content.charAt(i)) || content.charAt(i) == '.' || content.charAt(i) == 'e' || content.charAt(i) == 'E' || content.charAt(i) == '+' || content.charAt(i) == '-')) {
                    i++;
                }
                addHighlight(markupModel, start, i, COLOR_NUMBER, Font.PLAIN);
            } else if (content.substring(i).startsWith("true")) {
                addHighlight(markupModel, i, i + 4, COLOR_BOOLEAN, Font.BOLD);
                i += 4;
            } else if (content.substring(i).startsWith("false")) {
                addHighlight(markupModel, i, i + 5, COLOR_BOOLEAN, Font.BOLD);
                i += 5;
            } else if (content.substring(i).startsWith("null")) {
                addHighlight(markupModel, i, i + 4, COLOR_NULL, Font.ITALIC);
                i += 4;
            } else {
                i++;
            }
        }
    }

    private void addHighlight(MarkupModel markupModel, int start, int end, Color color, int fontStyle) {
        TextAttributes attributes = new TextAttributes();
        attributes.setForegroundColor(color);
        attributes.setFontType(fontStyle);
        markupModel.addRangeHighlighter(start, end, HighlighterLayer.SYNTAX, attributes, HighlighterTargetArea.EXACT_RANGE);
    }

    private List<int[]> findBracketRanges(String content) {
        List<int[]> ranges = new ArrayList<>();
        List<int[]> stack = new ArrayList<>();

        int line = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c == '{' || c == '[') {
                int type = c == '{' ? 0 : 1;
                stack.add(new int[]{i, type, line});
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    int[] opening = stack.remove(stack.size() - 1);
                    int expectedType = c == '}' ? 0 : 1;
                    if (opening[1] == expectedType && line > opening[2]) {
                        ranges.add(new int[]{opening[0], i + 1, opening[1]});
                    }
                }
            }
        }

        return ranges;
    }

    private void formatJson() {
        if (editor == null) return;

        String input = editor.getDocument().getText();

        if (input.trim().isEmpty()) {
            statusLabel.setText("");
            return;
        }

        try {
            String formatted = JsonUtil.prettify(input);

            WriteCommandAction.runWriteCommandAction(project, () -> {
                editor.getDocument().setText(formatted);
            });

            if (editor instanceof EditorEx editorEx) {
                editorEx.getMarkupModel().removeAllHighlighters();
                applyJsonHighlighting(editorEx, formatted);

                FoldingModelEx foldingModel = editorEx.getFoldingModel();
                foldingModel.runBatchFoldingOperation(() -> {
                    for (FoldRegion region : foldingModel.getAllFoldRegions()) {
                        foldingModel.removeFoldRegion(region);
                    }
                });
                setupFolding(editorEx, editor.getDocument(), formatted);
            }

            int arrayCount = JsonUtil.getArrayCount(input);
            if (arrayCount >= 0) {
                statusLabel.setText("Array: " + arrayCount + " items");
            } else {
                statusLabel.setText("");
            }
        } catch (Exception e) {
            statusLabel.setText("Error: " + e.getMessage());
        }
    }

    private void expandAllFolds() {
        if (editor == null) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            FoldingModel foldingModel = editor.getFoldingModel();
            foldingModel.runBatchFoldingOperation(() -> {
                for (FoldRegion region : foldingModel.getAllFoldRegions()) {
                    region.setExpanded(true);
                }
            });
        });
    }

    private void collapseAllFolds() {
        if (editor == null) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            FoldingModel foldingModel = editor.getFoldingModel();
            foldingModel.runBatchFoldingOperation(() -> {
                for (FoldRegion region : foldingModel.getAllFoldRegions()) {
                    region.setExpanded(false);
                }
            });
        });
    }

    private void copyResult() {
        if (editor != null) {
            String text = editor.getDocument().getText();
            if (!text.isEmpty()) {
                StringSelection selection = new StringSelection(text);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            }
        }
    }

    public void dispose() {
        if (editor != null) {
            EditorFactory.getInstance().releaseEditor(editor);
            editor = null;
        }
    }
}
