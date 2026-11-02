package io.snakeorcamel.agentterminal;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.Alarm;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Project-local, request-driven orchestration. Never creates or polls a terminal at startup. */
public final class AgentSessions implements Disposable {
    private final Project project;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
    private final ReadyDetector detector = new ReadyDetector();
    private final PendingRequests<Request> queue = new PendingRequests<>(System::nanoTime, TimeUnit.SECONDS.toNanos(30));
    private final Map<String, TerminalAccess.Session> profileTabs = new HashMap<>();
    private volatile boolean disposed;
    private volatile long generation;
    private boolean pollScheduled;
    private static final class Request {
        final TerminalAccess.Session session;
        AgentProfile profile;
        final Runnable retry;
        final boolean firstOnly;
        final boolean created;
        boolean initialized;
        boolean allowLaunch;
        Object connection;
        AutoCloseable watch;
        Request(TerminalAccess.Session session, AgentProfile profile, Runnable retry, boolean firstOnly, boolean allowLaunch, boolean created) {
            this.session = session; this.profile = profile; this.retry = retry;
            this.firstOnly = firstOnly; this.allowLaunch = allowLaunch;
            this.created = created;
        }
    }
    public AgentSessions(Project project) {
        this.project = project;
        ApplicationManager.getApplication().getMessageBus().connect(this).<Runnable>subscribe(AgentSettings.DISABLED, this::cancelAll);
    }
    private boolean enabled() { return !disposed && !project.isDisposed() && AgentSettings.getInstance().isEnabled(); }
    private TerminalAccess terminal() {
        TerminalAccess service = project.getService(TerminalAccess.class);
        if (service == null) throw new IllegalStateException("Enable the bundled Terminal plugin to use Agent Terminal. Existing SnakeOrCamel features remain available.");
        return service;
    }
    public void run(AgentProfile profile) {
        if (!enabled()) return;
        try {
            profile.validate();
            var session = profileTabs.get(profile.id);
            if (session != null && !session.exists()) { profileTabs.remove(profile.id); session = null; }
            boolean created = session == null;
            if (created) { session = terminal().create(directory(profile), profile.name); profileTabs.put(profile.id,session); }
            session.focus();
            if (hasRequest(session) || coolingDown(session, session.connection().identity())) return;
            offer(new Request(session,profile.copy(),() -> run(profile),false,true,created));
        } catch (RuntimeException | LinkageError ex) { report(ex,null,() -> run(profile)); }
    }
    private static final com.intellij.openapi.diagnostic.Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance(AgentSessions.class);
    private final List<Insertion> insertions = new ArrayList<>();
    private boolean showing;
    private long presentation;
    private record Launch(Object connection, long time) {}
    private final Map<Object, Launch> launches = new IdentityHashMap<>();
    private static final String CHANGED = "The target tab or connection changed. No reference was sent; press the shortcut again.";
    private static final class Insertion {
        TerminalAccess.Session session;
        final EditorReference reference;
        Object connection;
        final String key;
        final long requestedAt = System.nanoTime();
        boolean waitingForShell;
        boolean probing;
        boolean connectionSeen;
        AgentProfile startupProfile;
        long launchedAt;
        AutoCloseable watch;
        Insertion(TerminalAccess.Session session, EditorReference reference, Object connection, String key) {
            this.session = session; this.reference = reference; this.connection = connection; this.key = key;
        }
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
    public void insert(Editor editor) {
        if (!enabled() || editor.isDisposed()) return;
        EditorReference reference = null;
        Insertion pending = null;
        try {
            reference = new EditorReference(editor);
            var access = terminal();
            var session = access.first();
            String key = reference.format(null, AgentProfile.Kind.Custom);
            for (Insertion other : insertions) {
                if (other.key.equals(key) && (other.session == null || session != null
                        && other.session.identity() == session.identity())) return;
            }
            pending = new Insertion(session, reference, null, key);
            insertions.add(pending);
            Insertion request = pending;
            reference = null; // ownership transferred before any asynchronous or modal work
            if (session != null) bindInsertion(request, session, false);
            long epoch = generation;
            alarm.addRequest(() -> ApplicationManager.getApplication().invokeLater(() -> {
                if (epoch == generation) endInsertion(request,
                        "Terminal preparation timed out after 30 seconds. No reference was sent.");
            }, ModalityState.nonModal()), 30_000);
            if (!showing) {
                showing = true;
                long display = ++presentation;
                diagnostic(session, "window-show", TerminalAccess.ProcessStatus.UNKNOWN, null);
                access.show(() -> {
                    if (epoch != generation || display != presentation || !enabled()) return;
                    prepareInsertions(access);
                });
            }
        } catch (RuntimeException | LinkageError ex) {
            if (pending != null) {
                diagnostic(pending.session, "capture", TerminalAccess.ProcessStatus.UNKNOWN, ex);
                endInsertion(pending, "Terminal access failed. No reference was sent.");
            } else report(ex, reference, null);
        } finally { if (reference != null) reference.close(); }
    }
    private void bindInsertion(Insertion request, TerminalAccess.Session session, boolean preparing) {
        request.session = session;
        var connection = session.connection();
        request.connection = connection.identity();
        request.connectionSeen = connection.connected();
        request.waitingForShell = preparing || (!connection.connected() && session.starting());
        request.watch = terminal().watchFirst(session, () -> endInsertion(request, CHANGED));
        diagnostic(session, "tab-acquired", connection.process(), null);
    }
    private void prepareInsertions(TerminalAccess access) {
        long epoch = generation;
        long display = presentation;
        try {
            expireInsertions();
            if (insertions.isEmpty()) return;
            // Initialization may have supplied a tab while ToolWindow.show was pending.
            if (insertions.stream().anyMatch(r -> r.session == null)) {
                var session = access.first();
                AgentProfile profile = null;
                if (session == null) {
                    profile = AgentProfiles.getInstance(project).defaultProfile();
                    if (profile == null) profile = chooseDefault();
                    expireInsertions();
                    if (epoch != generation || display != presentation || !enabled() || insertions.isEmpty()) return;
                    if (profile == null) {
                        for (var request : List.copyOf(insertions)) if (request.session == null) endInsertion(request, null);
                        return;
                    }
                    profile.validate();
                    session = access.first(); // the modal chooser can initialize Terminal
                    if (session == null) {
                        session = access.create(directory(profile), profile.name);
                        profileTabs.put(profile.id, session);
                    }
                }
                for (var request : List.copyOf(insertions)) {
                    if (request.session == null) {
                        bindInsertion(request, session, true);
                        request.startupProfile = profile == null ? null : profile.copy();
                    }
                }
            }
            for (var request : List.copyOf(insertions)) {
                if (request.probing) continue;
                request.probing = true;
                if (!validInsertion(request)) { endInsertion(request, CHANGED); continue; }
                if (hasRequest(request.session)) {
                    endInsertion(request, "Press the shortcut again when the agent is ready. No reference has been queued.");
                    continue;
                }
                var connection = request.session.connection();
                if (!connection.connected() && !request.waitingForShell) {
                    endInsertion(request, "Terminal connection is unavailable. No reference was sent.");
                    continue;
                }
                Launch launch = launches.get(request.session.identity());
                if (launch != null && coolingDown(request.session, connection.identity())) request.launchedAt = launch.time();
                probeInsertion(request);
            }
        } catch (RuntimeException | LinkageError ex) {
            diagnostic(null, "tab-acquire", TerminalAccess.ProcessStatus.UNKNOWN, ex);
            for (var request : List.copyOf(insertions)) endInsertion(request, "Terminal preparation failed. No reference was sent.");
        } finally { if (display == presentation) showing = false; }
    }
    private void expireInsertions() {
        for (var request : List.copyOf(insertions)) {
            if (System.nanoTime() - request.requestedAt >= TimeUnit.SECONDS.toNanos(30)) {
                endInsertion(request, "Terminal preparation timed out after 30 seconds. No reference was sent.");
            }
        }
    }
    private void probeInsertion(Insertion request) {
        long epoch = generation;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (epoch != generation || !enabled()) return;
            boolean failed = false;
            try { request.session.refreshProcessState(); }
            catch (RuntimeException | LinkageError ex) {
                failed = true;
                diagnostic(request.session, "probe", TerminalAccess.ProcessStatus.UNKNOWN, ex);
            }
            boolean unknown = failed;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (epoch == generation && insertions.contains(request)) completeInsertion(request, unknown);
            }, ModalityState.nonModal());
        });
    }
    private void awaitStartup(Insertion request) {
        long epoch = generation;
        alarm.addRequest(() -> ApplicationManager.getApplication().invokeLater(() -> {
            if (epoch != generation || !insertions.contains(request)) return;
            try {
                if (validInsertion(request)) probeInsertion(request);
                else endInsertion(request, CHANGED);
            } catch (RuntimeException | LinkageError ex) {
                diagnostic(request.session, "startup", TerminalAccess.ProcessStatus.UNKNOWN, ex);
                endInsertion(request, CHANGED);
            }
        }, ModalityState.nonModal()), 200);
    }
    private boolean validInsertion(Insertion request) {
        return enabled() && insertions.contains(request) && request.session.exists() && terminal().isFirst(request.session);
    }
    private boolean coolingDown(TerminalAccess.Session session, Object connection) {
        Launch launch = launches.get(session.identity());
        return launch != null && launch.connection == connection
                && System.nanoTime() - launch.time < TimeUnit.SECONDS.toNanos(2);
    }
    private boolean emptyShellForInsertion(TerminalAccess.Session session) {
        try { return session.emptyShell(); }
        catch (RuntimeException | LinkageError ex) {
            diagnostic(session, "shell-input", TerminalAccess.ProcessStatus.IDLE, ex);
            return false;
        }
    }
    private void completeInsertion(Insertion request, boolean unknown) {
        try {
            if (!validInsertion(request)) { endInsertion(request, CHANGED); return; }
            if (System.nanoTime() - request.requestedAt >= TimeUnit.SECONDS.toNanos(30)) {
                endInsertion(request, "Agent startup could not be confirmed within 30 seconds. No reference was sent."); return;
            }
            var connection = request.session.connection();
            if (request.waitingForShell && request.connection == null) request.connection = connection.identity();
            if (connection.identity() != request.connection) { endInsertion(request, CHANGED); return; }
            if (!connection.connected() && request.connectionSeen) { endInsertion(request, CHANGED); return; }
            request.connectionSeen |= connection.connected();
            if (!connection.connected() && request.waitingForShell) {
                diagnostic(request.session, "connection-wait", connection.process(), null);
                awaitStartup(request); return;
            }
            if (!connection.connected()) { endInsertion(request, "Terminal connection is unavailable. No reference was sent."); return; }
            var state = unknown ? TerminalAccess.ProcessStatus.UNKNOWN : connection.process();
            diagnostic(request.session, "decide", state, null);
            if (request.launchedAt == 0) {
                Launch launch = launches.get(request.session.identity());
                if (launch != null && launch.connection() == request.connection && launch.time() >= request.requestedAt) {
                    request.launchedAt = launch.time();
                }
            }
            if (request.launchedAt != 0) {
                // Process state, not screen wording, gates delivery after our own launch.
                if (System.nanoTime() - request.launchedAt < TimeUnit.SECONDS.toNanos(2)
                        || state == TerminalAccess.ProcessStatus.UNKNOWN) { awaitStartup(request); return; }
                if (state == TerminalAccess.ProcessStatus.IDLE) {
                    endInsertion(request, "The default agent returned to the shell. No reference was sent. Check the launch command in Terminal."); return;
                }
            } else if (request.waitingForShell && state != TerminalAccess.ProcessStatus.IDLE) {
                if (state == TerminalAccess.ProcessStatus.UNKNOWN) { awaitStartup(request); return; }
                awaitStartup(request); return;
            }
            if (request.launchedAt == 0 && state == TerminalAccess.ProcessStatus.IDLE) {
                if (!emptyShellForInsertion(request.session)) {
                    if (request.waitingForShell) { awaitStartup(request); return; }
                    endInsertion(request, "Shell input was preserved because an empty command line could not be confirmed."); return;
                }
                var profile = request.startupProfile != null ? request.startupProfile : AgentProfiles.getInstance(project).defaultProfile();
                if (profile == null) {
                    profile = chooseDefault();
                    if (profile == null) { endInsertion(request, null); return; }
                    // Re-probe after a modal chooser before allowing any write.
                    probeInsertion(request); return;
                }
                profile.validate();
                if (!validInsertion(request) || request.session.connection().identity() != request.connection) {
                    endInsertion(request, CHANGED); return;
                }
                request.launchedAt = System.nanoTime();
                request.waitingForShell = false;
                launches.put(request.session.identity(), new Launch(request.connection, request.launchedAt));
                request.session.execute(profile.command);
                diagnostic(request.session, "launch", state, null);
                request.session.focus();
                AgentNotifications.show(project, "Starting the default agent. Your reference will be inserted once without Enter after startup.", null, null);
                awaitStartup(request);
                return;
            }
            request.reference.save();
            if (!validInsertion(request)) { endInsertion(request, CHANGED); return; }
            var fresh = request.session.connection();
            if (!fresh.connected() || fresh.identity() != request.connection) { endInsertion(request, CHANGED); return; }
            if (request.launchedAt != 0 && fresh.process() != TerminalAccess.ProcessStatus.RUNNING) {
                awaitStartup(request); return;
            }
            if ((!unknown && fresh.process() == TerminalAccess.ProcessStatus.IDLE) || coolingDown(request.session, fresh.identity())) {
                endInsertion(request, "Terminal state changed while saving. Press the shortcut again."); return;
            }
            String text = request.reference.format(request.session.directory(), AgentProfile.Kind.Custom);
            // Release before sending: ambiguous transmission failures must never retry automatically.
            endInsertion(request, null);
            try {
                request.session.insert(" " + text + " ");
                request.session.focus();
                diagnostic(request.session, "sent", state, null);
            } catch (RuntimeException | LinkageError ex) {
                diagnostic(request.session, "send", state, ex);
                AgentNotifications.show(project, "Reference transmission failed; the result may be uncertain. Check Terminal before retrying.", text, null);
            }
        } catch (RuntimeException | LinkageError ex) {
            diagnostic(request.session, "complete", TerminalAccess.ProcessStatus.UNKNOWN, ex);
            endInsertion(request, null);
            AgentNotifications.show(project, "Terminal access or file validation failed. No further input will be sent.", null, null);
        }
    }
    private void endInsertion(Insertion request, String message) {
        if (!insertions.remove(request)) return;
        if (insertions.isEmpty()) { showing = false; presentation++; }
        diagnostic(request.session, message == null ? "release" : "cancel", TerminalAccess.ProcessStatus.UNKNOWN, null);
        request.reference.close();
        if (request.watch != null) try { request.watch.close(); } catch (Exception ignored) { }
        if (message != null && enabled()) AgentNotifications.show(project, message, request.key, null);
    }
    private AgentProfile chooseDefault() {
        List<AgentProfile> profiles = AgentProfiles.getInstance(project).profiles();
        if (profiles.isEmpty()) throw new IllegalArgumentException("Add a launch profile in Settings → Tools → SnakeOrCamel → Agent Terminal.");
        int selected = Messages.showChooseDialog(project,"Select the default agent for an empty first terminal tab.","Agent Terminal",null,
                profiles.stream().map(p -> p.name).toArray(String[]::new),profiles.getFirst().name);
        if (selected < 0) return null;
        AgentProfile profile = profiles.get(selected);
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
    private boolean hasRequest(TerminalAccess.Session session) {
        return queue.entries().stream().anyMatch(e -> e.target() == session.identity());
    }
    private void offer(Request request) {
        if (!queue.offer(request.session.identity(), "launch", request)) return;
        try {
            request.connection = request.session.connection().identity();
            if (request.firstOnly) request.watch = terminal().watchFirst(request.session, () -> stop(request,"The target tab moved or closed. Retry to use the current first tab."));
        } catch (RuntimeException | LinkageError ex) { finish(request); throw ex; }
        schedule();
    }
    private void schedule() {
        if (pollScheduled || queue.isEmpty() || !enabled()) return;
        pollScheduled = true;
        long epoch = generation;
        var sampled = queue.entries();
        alarm.addRequest(() -> {
            Map<Request,String> failures = new IdentityHashMap<>();
            for (var entry : sampled) {
                if (epoch != generation || !enabled()) return;
                try { entry.value().session.refreshProcessState(); }
                catch (RuntimeException | LinkageError ex) { failures.put(entry.value(),ex.getMessage()); }
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (epoch == generation) poll(sampled,failures,epoch);
            }, ModalityState.nonModal());
        }, 200);
    }
    private void poll(List<PendingRequests.Entry<Request>> sampled, Map<Request,String> failures, long epoch) {
        if (!enabled()) { cancelAll(); return; }
        // Blocking probes have completed off EDT. UI capture and background recognition remain separate.
        var observations = new ArrayList<Observation>();
        for (var entry : sampled) {
            Request request = entry.value();
            if (!queue.contains(request)) continue;
            try {
                if (failures.containsKey(request)) { stop(request,failures.get(request)); continue; }
                if (queue.expired(entry)) { stop(request,"No empty shell was confirmed within 30 seconds. Agent launch stopped."); continue; }
                if (!valid(request)) { stop(request,"The target tab or session changed. Retry to use the current first tab."); continue; }
                TerminalAccess.Screen screen = request.session.screen();
                if (request.connection != null && request.session.connection().identity() != null && request.session.connection().identity() != request.connection) {
                    stop(request,"The terminal process changed. Retry to use the new session."); continue;
                }
                if (request.connection == null) request.connection = request.session.connection().identity();
                observations.add(new Observation(request,screen));
            } catch (RuntimeException | LinkageError ex) { stop(request,ex.getMessage()); }
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<Decision> decisions = observations.stream().map(o -> new Decision(o.request,detector.detect(o.screen,o.request.profile))).toList();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (epoch != generation) return;
                if (!enabled()) { cancelAll(); return; }
                for (Decision decision : decisions) handle(decision.request,decision.result);
                if (epoch == generation) { pollScheduled = false; schedule(); }
            },ModalityState.nonModal());
        });
    }
    private record Observation(Request request, TerminalAccess.Screen screen) {}
    private record Decision(Request request, ReadyDetector.Result result) {}
    private boolean valid(Request request) {
        return enabled() && request.session.exists() && (!request.firstOnly || terminal().isFirst(request.session));
    }
    private void handle(Request request, ReadyDetector.Result result) {
        if (!queue.contains(request)) return;
        try {
            if (expired(request)) { stop(request,"The agent launch request expired. Run the agent again."); return; }
            // Never use a stale background decision as permission to write.
            if (!valid(request)) { stop(request,"The target tab changed. Retry the reference."); return; }
            TerminalAccess.Screen fresh = request.session.screen();
            if (request.connection != null && request.session.connection().identity() != null && request.connection != request.session.connection().identity()) {
                stop(request,"The terminal process changed. Retry the reference."); return;
            }
            ReadyDetector.Result current = detector.detect(fresh,request.profile);
            if (!current.equals(result)) return;
            if (!request.initialized) {
                request.initialized = true;
                if (!request.created && current.state() != ReadyDetector.State.EMPTY_SHELL) {
                    request.allowLaunch = false;
                    finish(request);
                    AgentNotifications.show(project,"The profile tab is already in use. Its process and input were preserved.",null,null);
                    return;
                }
            }
            if (current.state() == ReadyDetector.State.EMPTY_SHELL && request.allowLaunch) {
                if (request.profile == null) {
                    request.profile = AgentProfiles.getInstance(project).defaultProfile();
                    if (request.profile == null) {
                        request.profile = chooseDefault();
                        if (request.profile == null) finish(request);
                        // A modal selection can take time: re-probe before executing on the next cycle.
                        return;
                    }
                }
                request.profile.validate();
                // A single request launches at most once, even if the command exits immediately.
                request.allowLaunch = false;
                launches.put(request.session.identity(), new Launch(request.session.connection().identity(), System.nanoTime()));
                request.session.execute(request.profile.command);
                diagnostic(request.session, "launch", TerminalAccess.ProcessStatus.IDLE, null);
                for (var other : queue.entries()) if (other.target() == request.session.identity()) other.value().allowLaunch = false;
                finish(request);
            } else if (current.state() == ReadyDetector.State.READY) {
                finish(request);
            }
        } catch (RuntimeException | LinkageError ex) { stop(request,ex.getMessage()); }
    }
    private boolean expired(Request request) {
        return queue.entries().stream().anyMatch(e -> e.value() == request && queue.expired(e));
    }
    private void stop(Request request, String reason) {
        if (!queue.contains(request)) return;
        finish(request);
        if (enabled()) AgentNotifications.show(project,reason == null ? "Terminal access failed; automatic insertion stopped." : reason,null,request.retry);
    }
    private void report(Throwable ex, EditorReference reference, Runnable retry) {
        String text = null;
        try { if (reference != null) text = reference.format(null,AgentProfile.Kind.Custom); } catch (RuntimeException ignored) { }
        AgentNotifications.show(project,ex.getMessage() == null ? "Terminal access failed. No further input will be sent." : ex.getMessage(),text,retry);
    }
    private void finish(Request request) { queue.remove(request); release(request); }
    private void release(Request request) {
        if (request.watch != null) try { request.watch.close(); } catch (Exception ignored) { }
    }
    public void cancelAll() {
        generation++;
        showing = false;
        alarm.cancelAllRequests(); pollScheduled = false;
        for (var entry : queue.entries()) finish(entry.value());
        for (var request : List.copyOf(insertions)) endInsertion(request, null);
    }
    @Override public void dispose() { disposed = true; cancelAll(); profileTabs.clear(); launches.clear(); }
}
