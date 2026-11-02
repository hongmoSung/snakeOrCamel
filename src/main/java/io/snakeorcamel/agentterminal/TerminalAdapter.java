package io.snakeorcamel.agentterminal;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.ui.content.*;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.nio.file.Path;
import java.util.*;

/** Loaded exclusively from the Terminal optional dependency descriptor. */
public final class TerminalAdapter implements TerminalAccess {
    private final Project project;
    public TerminalAdapter(Project project) { this.project = project; }
    private ToolWindow window() {
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
        if (window == null) throw new IllegalStateException("Terminal is not available in this IDE.");
        return window;
    }
    private List<Content> contents() {
        var window = window();
        var managers = new ArrayList<ContentManager>();
        var root = window.getContentManager();
        for (Content content : root.getContentsRecursively()) {
            if (content.getManager() != null && !managers.contains(content.getManager())) managers.add(content.getManager());
        }
        // Content order inside a manager is the visible tab order. Across splits use the top-left group.
        managers.sort(Comparator.<ContentManager>comparingInt(m -> position(m).y).thenComparingInt(m -> position(m).x));
        return managers.stream().flatMap(m -> Arrays.stream(m.getContents())).toList();
    }
    private Point position(ContentManager manager) {
        return SwingUtilities.convertPoint(manager.getComponent(), 0, 0, window().getComponent());
    }
    @Override public Session first() {
        var contents = contents(); return contents.isEmpty() ? null : wrap(contents.getFirst());
    }
    private Session wrap(Content content) {
        Session modern = ModernTerminalSession.find(project, content, () -> contents().contains(content), () -> focus(content));
        if (modern != null) return modern;
        var widget = TerminalToolWindowManager.getWidgetByContent(content);
        if (widget instanceof ShellTerminalWidget shell) return new ClassicTerminalSession(shell, content, () -> contents().contains(content), () -> focus(content));
        throw new IllegalStateException("This terminal engine is not supported. In IDEA 2024.2 switch Settings → Tools → Terminal to Classic.");
    }
    @Override public Session create(Path directory, String name) {
        window().show();
        Session modern = ModernTerminalSession.create(project, directory, name, c -> contents().contains(c), this::focus);
        if (modern != null) return modern;
        var widget = TerminalToolWindowManager.getInstance(project).createShellWidget(directory.toString(), name, true, true);
        for (Content content : contents()) if (TerminalToolWindowManager.getWidgetByContent(content) == ShellTerminalWidget.asShellJediTermWidget(widget)) return wrap(content);
        throw new IllegalStateException("Could not identify the new terminal tab. No command was sent.");
    }
    private void focus(Content content) {
        if (content.getManager() == null) return;
        content.getManager().setSelectedContent(content);
        window().activate(() -> content.getManager().requestFocus(content, true));
    }
    @Override public boolean isFirst(Session session) {
        Session first = first(); return first != null && first.identity() == session.identity();
    }
    @Override public AutoCloseable watchFirst(Session session, Runnable invalidated) {
        Set<ContentManager> managers = new HashSet<>(); managers.add(window().getContentManager());
        for (Content content : contents()) managers.add(content.getManager());
        ContentManagerListener listener = new ContentManagerListener() {
            private void check() {
                try { if (!isFirst(session)) invalidated.run(); }
                catch (RuntimeException ex) { invalidated.run(); }
            }
            @Override public void contentRemoved(ContentManagerEvent e) { check(); }
            @Override public void contentAdded(ContentManagerEvent e) { check(); }
        };
        managers.forEach(m -> m.addContentManagerListener(listener));
        return () -> managers.forEach(m -> m.removeContentManagerListener(listener));
    }
}
