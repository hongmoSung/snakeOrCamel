package io.snakeorcamel.agentterminal;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.*;
import java.util.*;

/** Holds live ranges, so delayed insertion refers to the saved document's current lines. EDT only. */
public final class EditorReference implements AutoCloseable {
    private final Document document;
    private final VirtualFile file;
    private final List<RangeMarker> ranges = new ArrayList<>();
    public EditorReference(Editor editor) {
        document = editor.getDocument();
        file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isInLocalFileSystem() || file.isDirectory()) {
            throw new IllegalArgumentException("Save this editor to a local file before sending a reference.");
        }
        for (Caret caret : editor.getCaretModel().getAllCarets()) if (caret.hasSelection()) {
            ranges.add(document.createRangeMarker(caret.getSelectionStart(), caret.getSelectionEnd()));
        }
        try { save(); } catch (RuntimeException ex) { close(); throw ex; }
    }
    public void save() {
        if (!file.isValid() || !Files.isRegularFile(Path.of(file.getPath()))) throw new IllegalArgumentException("The referenced file no longer exists on disk.");
        FileDocumentManager manager = FileDocumentManager.getInstance();
        manager.saveDocument(document);
        if (manager.isDocumentUnsaved(document)) throw new IllegalArgumentException("The file could not be saved; no reference was sent.");
    }
    public String format(Path directory, AgentProfile.Kind kind) {
        List<FileReferences.Lines> lines = new ArrayList<>();
        for (RangeMarker range : ranges) {
            if (!range.isValid() || range.getEndOffset() <= range.getStartOffset()) {
                throw new IllegalArgumentException("The selected range changed or was deleted. Select it again and retry.");
            }
            lines.add(new FileReferences.Lines(document.getLineNumber(range.getStartOffset()) + 1,
                    document.getLineNumber(range.getEndOffset() - 1) + 1));
        }
        return FileReferences.format(Path.of(file.getPath()), directory, lines, kind);
    }
    @Override public void close() { ranges.forEach(RangeMarker::dispose); ranges.clear(); }
}
