package io.snakeorcamel.agentterminal;

import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.*;

/** The sole reflection boundary for post-baseline public Terminal APIs. No accessibility overrides. */
final class ModernTerminalSession implements TerminalAccess.Session {
    private static final String MANAGER = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager";
    private static final String TAB = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab";
    private static final String BUILDER = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder";
    private static final String VIEW = "com.intellij.terminal.frontend.view.TerminalView";
    private static final String MODEL = "org.jetbrains.plugins.terminal.view.TerminalOutputModelSnapshot";
    private static final String OFFSET = "org.jetbrains.plugins.terminal.view.TerminalOffset";
    private static final String LINE = "org.jetbrains.plugins.terminal.view.TerminalLineIndex";
    private static final String INTEGRATION = "org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration";
    private final Object view;
    private final Content content;
    private final BooleanSupplier exists;
    private final Runnable focus;
    private ModernTerminalSession(Object tab, BooleanSupplier exists, Runnable focus) {
        view = call(TAB, tab, "getView"); content = (Content) call(TAB, tab, "getContent");
        this.exists = exists; this.focus = focus;
    }
    static TerminalAccess.Session find(Project project, Content content, BooleanSupplier exists, Runnable focus) {
        if (!available()) return null;
        Object manager = call(MANAGER, null, "getInstance", project);
        for (Object tab : (List<?>) call(MANAGER, manager, "getTabs")) {
            if (call(TAB, tab, "getContent") == content) return new ModernTerminalSession(tab, exists, focus);
        }
        return null;
    }
    static TerminalAccess.Session create(Project project, Path directory, String name, Predicate<Content> exists, Consumer<Content> focus) {
        if (!available()) return null;
        Object manager = call(MANAGER, null, "getInstance", project);
        Object builder = call(MANAGER, manager, "createTabBuilder");
        call(BUILDER, builder, "workingDirectory", directory.toString());
        call(BUILDER, builder, "tabName", name);
        call(BUILDER, builder, "requestFocus", true);
        call(BUILDER, builder, "deferSessionStartUntilUiShown", false);
        Object tab = call(BUILDER, builder, "createTab");
        Content content = (Content) call(TAB, tab, "getContent");
        return new ModernTerminalSession(tab, () -> exists.test(content), () -> focus.accept(content));
    }
    private static boolean available() {
        try { type(MANAGER); return true; } catch (ClassNotFoundException ex) { return false; }
    }
    @Override public Object identity() { return view; }
    @Override public boolean exists() { return content.getManager() != null && exists.getAsBoolean(); }
    @Override public TerminalAccess.Screen screen() {
        Object state = value(call(VIEW, view, "getSessionState"));
        if (!state.getClass().getSimpleName().equals("Running")) return TerminalAccess.Screen.unknown();
        Object models = call(VIEW, view, "getOutputModels");
        Object active = value(call("org.jetbrains.plugins.terminal.view.TerminalOutputModelsSet", models, "getActive"));
        Object snapshot = call("org.jetbrains.plugins.terminal.view.TerminalOutputModel", active, "takeSnapshot");
        Object cursor = call(MODEL, snapshot, "getCursorOffset");
        Object cursorRow = call(MODEL, snapshot, "getLineByOffset", cursor);
        Object rowStart = call(MODEL, snapshot, "getStartOfLine", cursorRow);
        Object rowEnd = call(MODEL, snapshot, "getEndOfLine", cursorRow, false);
        String line = call(MODEL, snapshot, "getText", rowStart, rowEnd).toString();
        String prefix = call(MODEL, snapshot, "getText", rowStart, cursor).toString();
        long first = number(LINE, call(MODEL, snapshot, "getFirstLineIndex"));
        long last = number(LINE, call(MODEL, snapshot, "getLastLineIndex"));
        Object grid = call(VIEW, view, "getGridSize");
        if (grid == null) return TerminalAccess.Screen.unknown();
        int rows = (Integer) call("org.jetbrains.plugins.terminal.session.TerminalGridSize", grid, "getRows");
        Object startLine = call(LINE, null, "of", Math.max(first, last-rows+1));
        Object start = call(MODEL, snapshot, "getStartOfLine", startLine);
        Object end = call(MODEL, snapshot, "getEndOffset");
        String screen = call(MODEL, snapshot, "getText", start, end).toString();
        int below = (int) call(MODEL,snapshot,"getText",rowEnd,end).toString().lines().filter(s -> !s.isBlank()).count();
        boolean idle = emptyShell(snapshot, end, active == call("org.jetbrains.plugins.terminal.view.TerminalOutputModelsSet", models, "getRegular"));
        return new TerminalAccess.Screen(screen, line, prefix, below, true, idle, commandRunning(),
                call(VIEW, view, "getStartupOptionsDeferred"));
    }
    private boolean commandRunning() {
        Object integration = integration();
        return integration != null && value(call(INTEGRATION,integration,"getOutputStatus")).getClass().getSimpleName().equals("ExecutingCommand");
    }
    private Object integration() {
        Object deferred = call(VIEW, view, "getShellIntegrationDeferred");
        if (!(Boolean) call("kotlinx.coroutines.Job", deferred, "isCompleted")
                || (Boolean) call("kotlinx.coroutines.Job", deferred, "isCancelled")) return null;
        return call("kotlinx.coroutines.Deferred", deferred, "getCompleted");
    }
    private boolean emptyShell(Object snapshot, Object end, boolean regular) {
        if (!regular) return false;
        Object integration = integration();
        if (integration == null) return false;
        Object status = value(call(INTEGRATION, integration, "getOutputStatus"));
        if (!status.getClass().getSimpleName().equals("TypingCommand")) return false;
        Object blocks = call(INTEGRATION, integration, "getBlocksModel");
        Object block = call("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalBlocksModel", blocks, "getActiveBlock");
        if (block == null || !isInstance("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock", block)) return false;
        Object commandStart = call("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock", block, "getCommandStartOffset");
        if (commandStart == null) return false;
        long from = number(OFFSET, commandStart);
        long to = number(OFFSET, end);
        if (from < number(OFFSET, call(MODEL, snapshot, "getStartOffset")) || from > to) return false;
        return call(MODEL, snapshot, "getText", commandStart, end).toString().isBlank();
    }
    @Override public Path directory() {
        Object path = value(call(VIEW, view, "getWorkingDirectoryFlow"));
        return path instanceof Path p ? p : null;
    }
    @Override public void execute(String command) {
        if (!screen().emptyShell()) throw new IllegalStateException("The shell is not empty and ready. No command was sent.");
        Object builder = call(VIEW, view, "createSendTextBuilder");
        call("org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder", builder, "shouldExecute");
        call("org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder", builder, "send", command);
    }
    @Override public void insert(String text) {
        if (AgentProfile.hasControl(text)) throw new IllegalArgumentException("Reference contains control characters.");
        call(VIEW, view, "sendText", text);
    }
    @Override public void focus() { focus.run(); }
    private static Object value(Object flow) { return call("kotlinx.coroutines.flow.StateFlow", flow, "getValue"); }
    private static long number(String contract, Object value) { return (Long) call(contract, value, "toAbsolute"); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, ModernTerminalSession.class.getClassLoader()); }
    private static boolean isInstance(String name, Object value) {
        try { return type(name).isInstance(value); } catch (ClassNotFoundException ex) { return false; }
    }
    private static Object call(String contract, Object target, String name, Object... args) {
        try {
            for (Method method : type(contract).getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                Class<?>[] types = method.getParameterTypes(); boolean matches = true;
                for (int i=0; i<args.length; i++) {
                    Class<?> t = types[i];
                    if (t == boolean.class) t = Boolean.class;
                    if (t == long.class) t = Long.class;
                    if (t == int.class) t = Integer.class;
                    if (args[i] != null && !t.isInstance(args[i])) { matches = false; break; }
                }
                if (matches) return method.invoke(target, args);
            }
            throw new NoSuchMethodException(contract + "." + name);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("The public Terminal API could not be accessed (" + name + "). No further input will be sent.", ex);
        }
    }
}
