package io.snakeorcamel.agentterminal;

import java.nio.file.Path;

/** Optional terminal service boundary. UI models stay on EDT; blocking process probes run on BGT without a read action. */
public interface TerminalAccess {
    record Screen(String text, String cursorLine, String beforeCursor, int linesBelowCursor,
                  boolean connected, boolean emptyShell, boolean commandRunning, Object connection) {
        public static Screen unknown() { return new Screen("", "", "", 0, false, false, false, null); }
    }
    enum ProcessStatus { RUNNING, IDLE, UNKNOWN }
    record Connection(Object identity, boolean connected, ProcessStatus process) {}
    interface Session {
        /** Connection and last process probe only: must not read terminal text. EDT only. */
        Connection connection();
        /** True only while a session has not established its initial connection. */
        default boolean starting() { return connection().identity() == null; }
        default String engine() { return getClass().getSimpleName(); }
        /** Read shell input only after an IDLE process probe. Uncertainty means false. */
        default boolean emptyShell() { return screen().emptyShell(); }
        Object identity();
        /** Refresh OS process information on a background thread, without accessing UI models. */
        default void refreshProcessState() {}
        /** Capture UI state on EDT, using only the last completed process probe. */
        Screen screen();
        Path directory();
        void execute(String command);
        void insert(String text);
        void focus();
        boolean exists();
    }
    /** Complete on EDT after the public ToolWindow show callback; caller owns cancellation. */
    void show(Runnable ready);
    Session first();
    Session create(Path directory, String name);
    boolean isFirst(Session session);
    AutoCloseable watchFirst(Session session, Runnable invalidated);
}
