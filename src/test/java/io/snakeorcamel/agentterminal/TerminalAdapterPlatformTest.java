package io.snakeorcamel.agentterminal;

import com.intellij.openapi.wm.ToolWindow;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Production adapter event ordering, including the transient removal during a split move. */
public class TerminalAdapterPlatformTest extends BasePlatformTestCase {
    public void testShowUsesPublicCompletionCallback() {
        var shown = new AtomicReference<Runnable>();
        var completed = new AtomicInteger();
        var window = proxy(ToolWindow.class, (method, args) -> {
            if (method.equals("show")) { assertEquals(1, args.length); shown.set((Runnable) args[0]); return null; }
            throw new AssertionError("Show must not inspect or initialize tabs: " + method);
        });
        var adapter = new TerminalAdapter(getProject(), () -> window);
        adapter.show(completed::incrementAndGet);
        assertEquals(0, completed.get()); shown.get().run(); assertEquals(1, completed.get());
    }
    public void testReorderAndSplitMoveSurviveButActualCloseInvalidatesOnNextEdt() throws Exception {
        var root = new Manager(); var split = new Manager();
        var owner = new AtomicReference<>(root);
        var content = proxy(Content.class, (method, args) -> {
            if (method.equals("getManager")) return owner.get() == null ? null : owner.get().proxy;
            throw new AssertionError(method);
        });
        root.contents.add(content);
        var window = proxy(ToolWindow.class, (method, args) -> {
            if (method.equals("getContentManagerIfCreated")) return root.proxy;
            throw new AssertionError(method);
        });
        var target = proxy(TerminalAccess.Session.class, (method, args) -> {
            if (method.equals("exists")) return owner.get() != null && root.contents.contains(content);
            throw new AssertionError(method);
        });
        var invalidated = new AtomicInteger();
        var watch = new TerminalAdapter(getProject(), () -> window).watch(target, invalidated::incrementAndGet);
        root.removed(); // Tab reorder, same Content.
        dispatch(); assertEquals(0, invalidated.get());
        root.contents.remove(content); owner.set(null); root.removed();
        assertEquals("Removal must be deferred", 0, invalidated.get());
        owner.set(split); split.contents.add(content); root.contents.add(content); root.added();
        dispatch(); assertEquals(0, invalidated.get());
        assertFalse("Watch must follow the new split manager", split.listeners.isEmpty());
        root.contents.clear(); split.contents.clear(); owner.set(null); split.removed();
        assertEquals(0, invalidated.get()); dispatch(); assertEquals(1, invalidated.get());
        assertTrue(root.listeners.isEmpty()); assertTrue(split.listeners.isEmpty()); watch.close();
    }
    public void testClosingWatchSuppressesDeferredRemoval() throws Exception {
        var root = new Manager(); var alive = new AtomicReference<>(true);
        var window = proxy(ToolWindow.class, (method, args) -> {
            if (method.equals("getContentManagerIfCreated")) return root.proxy;
            throw new AssertionError(method);
        });
        var target = proxy(TerminalAccess.Session.class, (method, args) -> {
            if (method.equals("exists")) return alive.get();
            throw new AssertionError(method);
        });
        var invalidated = new AtomicInteger();
        var watch = new TerminalAdapter(getProject(), () -> window).watch(target, invalidated::incrementAndGet);
        alive.set(false); root.removed(); watch.close(); dispatch();
        assertEquals(0, invalidated.get()); assertTrue(root.listeners.isEmpty());
    }
    private static void dispatch() { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); }
    private static final class Manager {
        final List<Content> contents = new ArrayList<>();
        final List<ContentManagerListener> listeners = new ArrayList<>();
        final ContentManager proxy = proxy(ContentManager.class, (method, args) -> {
            return switch (method) {
                case "getContentsRecursively" -> List.copyOf(contents);
                case "addContentManagerListener" -> { listeners.add((ContentManagerListener) args[0]); yield null; }
                case "removeContentManagerListener" -> { listeners.remove(args[0]); yield null; }
                default -> throw new AssertionError(method);
            };
        });
        void removed() { List.copyOf(listeners).forEach(l -> l.contentRemoved(null)); }
        void added() { List.copyOf(listeners).forEach(l -> l.contentAdded(null)); }
    }
    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> type.getSimpleName();
                default -> call.invoke(method.getName(), args);
            };
        }));
    }
}
