package io.snakeorcamel.agentterminal;

import com.intellij.openapi.wm.ToolWindow;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.ContentManager;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the production adapter's public ToolWindow handoff without starting a shell. */
public class TerminalAdapterPlatformTest extends BasePlatformTestCase {
    public void testTabLookupWaitsForToolWindowShowCompletion() {
        var shown = new AtomicReference<Runnable>();
        var lookups = new AtomicInteger();
        var completed = new AtomicInteger();
        var manager = (ContentManager) Proxy.newProxyInstance(ContentManager.class.getClassLoader(),
                new Class<?>[]{ContentManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getContentsRecursively")) return List.of();
                    throw new AssertionError("Unexpected content access: " + method.getName());
                });
        var window = (ToolWindow) Proxy.newProxyInstance(ToolWindow.class.getClassLoader(),
                new Class<?>[]{ToolWindow.class}, (proxy, method, args) -> {
                    if (method.getName().equals("show")) {
                        assertNotNull("The callback overload is required", args);
                        assertEquals(1, args.length);
                        shown.set((Runnable) args[0]);
                        return null;
                    }
                    if (method.getName().equals("getContentManager")) {
                        lookups.incrementAndGet();
                        return manager;
                    }
                    throw new AssertionError("Unexpected window access: " + method.getName());
                });
        var adapter = new TerminalAdapter(getProject(), () -> window);
        adapter.show(() -> {
            assertNull(adapter.first());
            completed.incrementAndGet();
        });
        assertNotNull(shown.get());
        assertEquals(0, completed.get());
        assertEquals(0, lookups.get());
        shown.get().run();
        assertEquals(1, completed.get());
        assertEquals(1, lookups.get());
    }
}
