package io.snakeorcamel.agentterminal;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.util.Alarm;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** EDT-owned, project-local ownership. Only blocking process probes leave EDT. */
public final class AgentSessions implements Disposable {
    private static final com.intellij.openapi.diagnostic.Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance(AgentSessions.class);
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(30);
    private static final long GRACE = TimeUnit.SECONDS.toNanos(2);
    private static final String CHANGED = "The agent tab closed or its connection changed. No further reference was sent; press the shortcut again.";
    private final Project project;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
    private final LongSupplier clock;
    private final java.util.function.Function<List<AgentProfile>, AgentProfile> chooser;
    private final Map<String, Managed> profileTabs = new HashMap<>();
    private final List<Work> pending = new ArrayList<>();
    private volatile boolean disposed;
    private volatile long generation;

    private static final class Managed {
        final TerminalAccess.Session session;
        final AgentProfile profile;
        final Path directory;
        Object connection;
        boolean connectedSeen;
        boolean launchAttempted;
        boolean confirmed;
        boolean failed;
        long launchedAt;
        Managed(TerminalAccess.Startup startup, AgentProfile profile, Path directory) {
            this.session = startup.session(); this.profile = profile; this.directory = directory;
            launchAttempted = startup.commandQueued();
            var current = session.connection();
            connection = current.identity(); connectedSeen = current.connected();
        }
        boolean matches(AgentProfile other, Path path) {
            return profile.id.equals(other.id) && profile.command.equals(other.command) && directory.equals(path);
        }
    }
    private static final class Work {
        final AgentProfile profile;
        final Path directory;
        final long requestedAt;
        final LinkedHashMap<String, EditorReference> references = new LinkedHashMap<>();
        Managed target;
        AutoCloseable watch;
        boolean created;
        boolean begun;
        Work(AgentProfile profile, Path directory, long requestedAt) {
            this.profile = profile; this.directory = directory; this.requestedAt = requestedAt;
        }
    }
    public AgentSessions(Project project) { this(project, System::nanoTime); }
    AgentSessions(Project project, LongSupplier clock) {
        this(project, clock, choices -> AgentProfileDialog.choose(project, choices, "Agent Terminal",
                "Select the default agent for a dedicated terminal tab."));
    }
    AgentSessions(Project project, LongSupplier clock, java.util.function.Function<List<AgentProfile>, AgentProfile> chooser) {
        this.project = project; this.clock = clock; this.chooser = chooser;
        ApplicationManager.getApplication().getMessageBus().connect(this).<Runnable>subscribe(AgentSettings.DISABLED, this::cancelAll);
    }
    private boolean enabled() { return !disposed && !project.isDisposed() && AgentSettings.getInstance().isEnabled(); }
    private TerminalAccess terminal() {
        TerminalAccess service = project.getService(TerminalAccess.class);
        if (service == null) throw new IllegalStateException("Enable the bundled Terminal plugin to use Agent Terminal. Existing SnakeOrCamel features remain available.");
        return service;
    }
    /** Log metadata and stack locations only; exception messages may contain terminal text or paths. */
    static void diagnostic(TerminalAccess.Session session, String stage, TerminalAccess.ProcessStatus state, Throwable error) {
        String message = "Agent Terminal engine=" + (session == null ? "pending" : session.engine()) + " state=" + state + " stage=" + stage;
        if (error == null) LOG.debug(message);
        else {
            Throwable sanitized = new Throwable(error.getClass().getName());
            sanitized.setStackTrace(error.getStackTrace());
            LOG.info(message, sanitized);
        }
    }
    public void run(AgentProfile profile) {
        if (!enabled()) return;
        try { request(profile.copy(), null); }
        catch (RuntimeException | LinkageError ex) { report(ex); }
    }
    public void insert(Editor editor) {
        if (!enabled() || editor.isDisposed()) return;
        EditorReference reference = null;
        try {
            reference = new EditorReference(editor);
            long epoch = generation;
            AgentProfile profile = AgentProfiles.getInstance(project).defaultProfile();
            if (profile == null) profile = chooseDefault();
            if (profile == null || epoch != generation || !enabled()) return;
            if (request(profile.copy(), reference)) reference = null;
        } catch (RuntimeException | LinkageError ex) { report(ex); }
        finally { if (reference != null) reference.close(); }
    }
    /** Takes ownership of the reference's ranges only after validation and deduplication. */
    private boolean request(AgentProfile profile, EditorReference reference) {
        profile.validate();
        Path path = directory(profile);
        String key = reference == null ? null : reference.format(null, AgentProfile.Kind.Custom);
        Work work = pending.stream().filter(w -> w.profile.id.equals(profile.id)
                && w.profile.command.equals(profile.command) && w.directory.equals(path)).findFirst().orElse(null);
        if (work != null) {
            if (clock.getAsLong() - work.requestedAt >= TIMEOUT) {
                finish(work, "Agent startup could not be confirmed within 30 seconds. No further reference was sent.");
                return false;
            }
            if (reference == null || work.references.containsKey(key)) return false;
            work.references.put(key, reference);
            return true;
        }
        work = new Work(profile, path, clock.getAsLong());
        if (reference != null) work.references.put(key, reference);
        pending.add(work);
        Work current = work;
        long epoch = generation;
        // Independent of probes/show callbacks, so a stuck background query cannot extend startup.
        alarm.addRequest(() -> later(() -> {
            if (epoch == generation) finish(current, "Agent startup could not be confirmed within 30 seconds. No further reference was sent.");
        }), 30_000);
        start(current);
        return reference != null;
    }
    private void start(Work current) {
        if (current.begun || !enabled() || !pending.contains(current)) return;
        // A changed launch configuration queues behind the accepted startup for this profile.
        for (Work earlier : pending) {
            if (earlier == current) break;
            if (earlier.profile.id.equals(current.profile.id)) return;
        }
        if (clock.getAsLong() - current.requestedAt >= TIMEOUT) {
            finish(current, "Agent startup could not be confirmed within 30 seconds. No further reference was sent."); return;
        }
        current.begun = true;
        long epoch = generation;
        try {
            Managed owned = profileTabs.get(current.profile.id);
            if (owned == null || owned.failed || !owned.matches(current.profile, current.directory) || !owned.session.exists()) create(current);
            else bind(current, owned);
            terminal().show(() -> {
                if (!active(current, epoch)) return;
                try { current.target.session.focus(); probe(current); }
                catch (RuntimeException | LinkageError ex) { fail(current, ex); }
            });
        } catch (RuntimeException | LinkageError ex) { fail(current, ex); }
    }
    private void create(Work work) {
        work.created = true;
        var startup = terminal().createForStartup(work.directory, work.profile.name, work.profile.command);
        Managed owned = new Managed(startup, work.profile, work.directory);
        if (owned.launchAttempted && owned.connectedSeen) owned.launchedAt = clock.getAsLong();
        profileTabs.put(work.profile.id, owned);
        bind(work, owned);
    }
    private void bind(Work work, Managed target) {
        closeWatch(work);
        work.target = target;
        work.watch = terminal().watch(target.session, () -> {
            // Adapter checks on the next EDT turn, after a split/reorder has reattached Content.
            if (pending.contains(work)) finish(work, CHANGED);
        });
        diagnostic(target.session, "tab-acquired", target.session.connection().process(), null);
    }
    private static void later(Runnable task) {
        ApplicationManager.getApplication().invokeLater(task, ModalityState.nonModal());
    }
    private boolean active(Work work, long epoch) { return epoch == generation && enabled() && pending.contains(work); }
    private void probe(Work work) {
        long epoch = generation;
        Managed target = work.target;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (epoch != generation || !enabled()) return;
            boolean failed = false;
            try { target.session.refreshProcessState(); }
            catch (RuntimeException | LinkageError ex) {
                failed = true;
                diagnostic(target.session, "probe", TerminalAccess.ProcessStatus.UNKNOWN, ex);
            }
            boolean unknown = failed;
            later(() -> {
                if (active(work, epoch) && work.target == target) complete(work, unknown);
            });
        });
    }
    private void await(Work work) {
        long epoch = generation;
        alarm.addRequest(() -> later(() -> {
            if (active(work, epoch)) probe(work);
        }), 200);
    }
    private boolean connected(Managed target) {
        if (!target.session.exists()) return false;
        var connection = target.session.connection();
        if (target.connection == null) target.connection = connection.identity();
        if (connection.identity() != target.connection || !connection.connected() && target.connectedSeen) {
            profileTabs.remove(target.profile.id, target);
            return false;
        }
        if (connection.connected() && !target.connectedSeen && target.launchAttempted) target.launchedAt = clock.getAsLong();
        target.connectedSeen |= connection.connected();
        return true;
    }
    private void complete(Work work, boolean unknown) {
        try {
            if (!enabled()) { cancelAll(); return; }
            if (clock.getAsLong() - work.requestedAt >= TIMEOUT) {
                finish(work, "Agent startup could not be confirmed within 30 seconds. No further reference was sent."); return;
            }
            Managed target = work.target;
            if (!connected(target)) { finish(work, CHANGED); return; }
            var connection = target.session.connection();
            if (!connection.connected()) { await(work); return; }
            var state = unknown ? TerminalAccess.ProcessStatus.UNKNOWN : connection.process();
            diagnostic(target.session, "decide", state, null);
            if (state == TerminalAccess.ProcessStatus.UNKNOWN) { await(work); return; }
            if (!target.launchAttempted) {
                // Classic only: launch once in our new, connected, confirmed empty shell.
                if (state != TerminalAccess.ProcessStatus.IDLE || !target.session.emptyShell()) { await(work); return; }
                target.launchAttempted = true; // Set before sending; an ambiguous failure must never retry.
                target.launchedAt = clock.getAsLong();
                target.session.execute(target.profile.command);
                diagnostic(target.session, "launch", state, null);
                await(work); return;
            }
            if (!target.confirmed) {
                if (clock.getAsLong() - target.launchedAt < GRACE) { await(work); return; }
                if (state == TerminalAccess.ProcessStatus.IDLE) {
                    target.failed = true;
                    finish(work, "The agent returned to the shell. No reference was sent. Check the launch command in Terminal."); return;
                }
                target.confirmed = true;
            } else if (state == TerminalAccess.ProcessStatus.IDLE) {
                if (work.created) {
                    target.failed = true;
                    finish(work, "The agent returned to the shell. No further reference was sent."); return;
                }
                // Preserve the old tab and its shell input. Replacement is allowed only after a known exit.
                create(work);
                work.target.session.focus();
                await(work); return;
            }
            deliver(work);
        } catch (RuntimeException | LinkageError ex) { fail(work, ex); }
    }
    private void deliver(Work work) {
        Managed target = work.target;
        for (var entry : new ArrayList<>(work.references.entrySet())) {
            EditorReference reference = entry.getValue();
            reference.save();
            if (!enabled() || !pending.contains(work) || !connected(target)) { finish(work, CHANGED); return; }
            var fresh = target.session.connection();
            if (!fresh.connected() || fresh.process() != TerminalAccess.ProcessStatus.RUNNING) { await(work); return; }
            String text = reference.format(target.session.directory(), work.profile.kind);
            // Release before sending. A partially transmitted reference is never retried automatically.
            work.references.remove(entry.getKey()); reference.close();
            try { target.session.insert(" " + text + " "); }
            catch (RuntimeException | LinkageError ex) {
                diagnostic(target.session, "send", fresh.process(), ex);
                finish(work, "Reference transmission failed; the result may be uncertain. Check Terminal before retrying."); return;
            }
            diagnostic(target.session, "sent", fresh.process(), null);
        }
        finish(work, null);
    }
    private void fail(Work work, Throwable error) {
        diagnostic(work.target == null ? null : work.target.session, "request", TerminalAccess.ProcessStatus.UNKNOWN, error);
        finish(work, "Terminal access or file validation failed. No further input will be sent.");
    }
    private void finish(Work work, String message) {
        if (!pending.remove(work)) return;
        closeWatch(work);
        work.references.values().forEach(EditorReference::close); work.references.clear();
        if (message != null && enabled()) AgentNotifications.show(project, message, null, null);
        long epoch = generation;
        Work next = pending.stream().filter(w -> w.profile.id.equals(work.profile.id)).findFirst().orElse(null);
        if (next != null) later(() -> { if (active(next, epoch)) start(next); });
    }
    private static void closeWatch(Work work) {
        if (work.watch != null) try { work.watch.close(); } catch (Exception ignored) { }
        work.watch = null;
    }
    private AgentProfile chooseDefault() {
        List<AgentProfile> profiles = AgentProfiles.getInstance(project).profiles();
        if (profiles.isEmpty()) throw new IllegalArgumentException("Add a launch profile in Settings → Tools → SnakeOrCamel → Agent Terminal.");
        AgentProfile profile = chooser.apply(profiles);
        if (profile == null) return null;
        AgentProfiles.getInstance(project).getState().defaultId = profile.id;
        return profile;
    }
    private Path directory(AgentProfile profile) {
        String value = profile.directory.isBlank() ? project.getBasePath() : profile.directory;
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Choose a working directory in the launch profile.");
        Path directory = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Working directory does not exist: " + directory);
        return directory;
    }
    private void report(Throwable ex) {
        AgentNotifications.show(project, ex.getMessage() == null ? "Terminal access failed. No further input will be sent." : ex.getMessage(), null, null);
    }
    public void cancelAll() {
        generation++;
        alarm.cancelAllRequests();
        for (Work work : List.copyOf(pending)) finish(work, null);
    }
    @Override public void dispose() { disposed = true; cancelAll(); profileTabs.clear(); }
}
