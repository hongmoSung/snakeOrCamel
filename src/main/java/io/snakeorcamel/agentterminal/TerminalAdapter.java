package io.snakeorcamel.agentterminal;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.ui.content.*;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import java.nio.file.Path;
import java.util.*;

/** Loaded exclusively from the Terminal optional dependency descriptor. */
public final class TerminalAdapter implements TerminalAccess {
    private final Project project;
    private final java.util.function.Supplier<ToolWindow> windowProvider;
    public TerminalAdapter(Project project) {
        this(project, () -> ToolWindowManager.getInstance(project).getToolWindow("Terminal"));
    }
    TerminalAdapter(Project project, java.util.function.Supplier<ToolWindow> windowProvider) {
        this.project = project;
        this.windowProvider = windowProvider;
    }
    private ToolWindow window() {
        ToolWindow window = windowProvider.get();
        if (window == null) throw new IllegalStateException("Terminal is not available in this IDE.");
        return window;
    }
    private List<Content> contents() {
        var window = window();
        var root = window.getContentManagerIfCreated();
        return root == null ? List.of() : List.copyOf(root.getContentsRecursively());
    }
    @Override public void show(Runnable ready) { window().show(ready); }
    private Session createClassic(Path directory, String name) {
        var widget = TerminalToolWindowManager.getInstance(project).createShellWidget(directory.toString(), name, true, true);
        var shell = ShellTerminalWidget.asShellJediTermWidget(widget);
        if (shell != null) for (Content content : contents()) {
            if (TerminalToolWindowManager.getWidgetByContent(content) == shell) {
                return new ClassicTerminalSession(shell, content, () -> contents().contains(content), () -> focus(content));
            }
        }
        throw new IllegalStateException("Could not identify the new terminal tab. No command was sent.");
    }
    @Override public Startup createForStartup(Path directory, String name, String command) {
        if (org.jetbrains.plugins.terminal.TerminalOptionsProvider.getInstance().getTerminalEngine()
                == org.jetbrains.plugins.terminal.TerminalEngine.REWORKED) {
            return new Startup(ModernTerminalSession.createForStartup(project, directory, name, command,
                    c -> contents().contains(c), this::focus), true);
        }
        return new Startup(createClassic(directory, name), false);
    }
    private void focus(Content content) {
        if (content.getManager() == null) return;
        content.getManager().setSelectedContent(content);
        window().activate(() -> {
            var manager = content.getManager();
            if (manager != null) manager.requestFocus(content, true);
        });
    }
    @Override public AutoCloseable watch(Session session, Runnable invalidated) {
        Set<ContentManager> managers = new HashSet<>();
        class Watch implements ContentManagerListener, AutoCloseable {
            boolean closed;
            boolean scheduled;
            void attach() {
                var root = window().getContentManagerIfCreated();
                if (root != null && managers.add(root)) root.addContentManagerListener(this);
                for (Content content : contents()) {
                    var manager = content.getManager();
                    if (manager != null && managers.add(manager)) manager.addContentManagerListener(this);
                }
            }
            void checkLater() {
                if (closed || scheduled) return;
                scheduled = true;
                ApplicationManager.getApplication().invokeLater(() -> {
                    scheduled = false;
                    if (closed) return;
                    try {
                        attach();
                        if (session.exists()) return;
                    } catch (RuntimeException ex) { /* Treat inaccessible content as removed. */ }
                    close();
                    invalidated.run();
                }, ModalityState.nonModal());
            }
            @Override public void contentRemoved(ContentManagerEvent e) { checkLater(); }
            @Override public void contentAdded(ContentManagerEvent e) { checkLater(); }
            @Override public void close() {
                closed = true;
                managers.forEach(m -> m.removeContentManagerListener(this));
                managers.clear();
            }
        }
        Watch watch = new Watch(); watch.attach(); return watch;
    }
}
