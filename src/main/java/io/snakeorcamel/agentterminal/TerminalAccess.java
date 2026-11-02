package io.snakeorcamel.agentterminal;

import java.nio.file.Path;

/** Optional terminal service boundary. All access to mutable terminal UI models is on EDT. */
public interface TerminalAccess {
    record Screen(String text, String cursorLine, String beforeCursor, int linesBelowCursor,
                  boolean connected, boolean emptyShell, boolean commandRunning, Object connection) {
        public static Screen unknown() { return new Screen("", "", "", 0, false, false, false, null); }
    }
    interface Session {
        Object identity();
        Screen screen();
        Path directory();
        void execute(String command);
        void insert(String text);
        void focus();
        boolean exists();
    }
    Session first();
    Session create(Path directory, String name);
    boolean isFirst(Session session);
    AutoCloseable watchFirst(Session session, Runnable invalidated);
}
