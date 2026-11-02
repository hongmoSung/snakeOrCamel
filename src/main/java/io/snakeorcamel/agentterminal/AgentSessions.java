package io.snakeorcamel.agentterminal;

import com.intellij.notification.*;
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
    private final Map<Object, AgentProfile> launchedProfiles = new IdentityHashMap<>();
    private boolean disposed;
    private boolean pollScheduled;
    private static final class Request {
        final TerminalAccess.Session session;
        final AgentProfile profile;
        final EditorReference reference;
        final Runnable retry;
        final boolean firstOnly;
        boolean allowLaunch;
        Object connection;
        AutoCloseable watch;
        Notification notice;
        Request(TerminalAccess.Session session, AgentProfile profile, EditorReference reference, Runnable retry, boolean firstOnly, boolean allowLaunch) {
            this.session = session; this.profile = profile; this.reference = reference; this.retry = retry;
            this.firstOnly = firstOnly; this.allowLaunch = allowLaunch;
        }
    }
    public AgentSessions(Project project) {
        this.project = project;
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(AgentSettings.DISABLED, this::cancelAll);
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
            if (hasRequest(session)) return;
            if (!created && !session.screen().emptyShell()) {
                AgentNotifications.show(project,"The profile tab is already in use. Its process and input were preserved.",null,null); return;
            }
            offer(new Request(session,profile.copy(),null,() -> run(profile),false,true));
        } catch (RuntimeException | LinkageError ex) { report(ex,null,() -> run(profile)); }
    }
    public void insert(Editor editor) {
        if (!enabled() || editor.isDisposed()) return;
        EditorReference reference = null;
        Runnable retry = () -> { if (!editor.isDisposed()) insert(editor); };
        try {
            reference = new EditorReference(editor);
            var terminal = terminal();
            var session = terminal.first();
            AgentProfile profile = session == null ? null : launchedProfiles.get(session.identity());
            boolean created = session == null;
            if (created || session.screen().emptyShell()) {
                if (profile == null) profile = AgentProfiles.getInstance(project).defaultProfile();
                if (profile == null) profile = chooseDefault();
                if (profile == null) { reference.close(); return; }
                profile.validate();
                if (created) {
                    session = terminal.create(directory(profile), profile.name);
                    profileTabs.put(profile.id,session);
                }
            }
            Request request = new Request(session,profile == null ? null : profile.copy(),reference,retry,true,
                    (created || session.screen().emptyShell()) && !hasRequest(session));
            offer(request);
        } catch (RuntimeException | LinkageError ex) {
            report(ex, reference,retry); if (reference != null) reference.close();
        }
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
        String key = request.reference == null ? "launch" : request.reference.format(null,AgentProfile.Kind.Custom);
        if (!queue.offer(request.session.identity(),key,request)) {
            if (request.reference != null) request.reference.close(); return;
        }
        try {
            request.connection = request.session.screen().connection();
            if (request.firstOnly) request.watch = terminal().watchFirst(request.session, () -> stop(request,"The target tab moved or closed. Retry to use the current first tab."));
        } catch (RuntimeException | LinkageError ex) { finish(request); throw ex; }
        schedule();
    }
    private void schedule() {
        if (pollScheduled || queue.isEmpty() || !enabled()) return;
        pollScheduled = true;
        alarm.addRequest(() -> ApplicationManager.getApplication().invokeLater(this::poll, ModalityState.nonModal()), 200);
    }
    private void poll() {
        pollScheduled = false;
        if (!enabled()) { cancelAll(); return; }
        // Mutable terminal models must be read on EDT. Recognition runs on immutable snapshots off EDT.
        var observations = new ArrayList<Observation>();
        for (var entry : queue.entries()) {
            Request request = entry.value();
            try {
                if (queue.expired(entry)) { stop(request,"No ready input was detected within 30 seconds. Automatic insertion stopped."); continue; }
                if (!valid(request)) { stop(request,"The target tab or session changed. Retry to use the current first tab."); continue; }
                TerminalAccess.Screen screen = request.session.screen();
                if (request.connection != null && screen.connection() != null && screen.connection() != request.connection) {
                    stop(request,"The terminal process changed. Retry to use the new session."); continue;
                }
                if (request.connection == null) request.connection = screen.connection();
                observations.add(new Observation(request,screen));
            } catch (RuntimeException | LinkageError ex) { stop(request,ex.getMessage()); }
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<Decision> decisions = observations.stream().map(o -> new Decision(o.request,detector.detect(o.screen,o.request.profile))).toList();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!enabled()) { cancelAll(); return; }
                for (Decision decision : decisions) handle(decision.request,decision.result);
                schedule();
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
            if (expired(request)) { stop(request,"The 30-second request expired. Retry to insert a reference."); return; }
            // Never use a stale background decision as permission to write.
            if (!valid(request)) { stop(request,"The target tab changed. Retry the reference."); return; }
            TerminalAccess.Screen fresh = request.session.screen();
            if (request.connection != null && fresh.connection() != null && request.connection != fresh.connection()) {
                stop(request,"The terminal process changed. Retry the reference."); return;
            }
            ReadyDetector.Result current = detector.detect(fresh,request.profile);
            if (!current.equals(result)) return;
            if (current.state() == ReadyDetector.State.EMPTY_SHELL && request.allowLaunch) {
                // A single request launches at most once, even if the command exits immediately.
                request.allowLaunch = false;
                launchedProfiles.put(request.session.identity(),request.profile.copy());
                request.session.execute(request.profile.command);
                for (var other : queue.entries()) if (other.target() == request.session.identity()) other.value().allowLaunch = false;
            } else if (current.state() == ReadyDetector.State.READY) {
                if (request.reference != null) send(request,current.kind(),false); else finish(request);
            } else if (request.notice == null && request.reference != null) {
                String message = current.state() == ReadyDetector.State.CONFIRMATION
                        ? "Complete login or permission/trust confirmation in Terminal. Your reference is held for up to 30 seconds."
                        : "Waiting for an agent question input (up to 30 seconds). Existing terminal input is preserved.";
                Runnable manualInsert = null;
                if (request.profile != null && request.profile.kind == AgentProfile.Kind.Custom && request.profile.readyPattern.isBlank()
                        && launchedProfiles.containsKey(request.session.identity())) {
                    manualInsert = () -> manual(request);
                }
                request.notice = AgentNotifications.show(project,message,copy(request),null,manualInsert);
            }
        } catch (RuntimeException | LinkageError ex) { stop(request,ex.getMessage()); }
    }
    private void manual(Request request) {
        try {
        if (!queue.contains(request) || !valid(request)) return;
        if (expired(request)) { stop(request,"The reference expired. Retry to create a new request."); return; }
        var result = detector.detect(request.session.screen(),request.profile);
        if (result.state() == ReadyDetector.State.CONFIRMATION || result.state() == ReadyDetector.State.EMPTY_SHELL) return;
        if (Messages.showYesNoDialog(project,"Check that the first terminal tab is at your Custom agent's question input. Insert without Enter?",
                "Insert Reference",null) != Messages.YES) return;
        if (!queue.contains(request) || !valid(request) || expired(request)) return;
        result = detector.detect(request.session.screen(),request.profile);
        if (result.state() != ReadyDetector.State.CONFIRMATION && result.state() != ReadyDetector.State.EMPTY_SHELL
                && request.session.screen().connected()) send(request,AgentProfile.Kind.Custom,true);
        } catch (RuntimeException | LinkageError ex) { stop(request,ex.getMessage()); }
    }
    private boolean expired(Request request) {
        return queue.entries().stream().anyMatch(e -> e.value() == request && queue.expired(e));
    }
    private void send(Request request, AgentProfile.Kind kind, boolean manual) {
        request.reference.save();
        if (!queue.contains(request)) return;
        if (!valid(request) || expired(request)) { stop(request,"The target changed or the request expired while saving. Retry the reference."); return; }
        TerminalAccess.Screen screen = request.session.screen();
        if (request.connection != null && screen.connection() != request.connection) {
            stop(request,"The terminal process changed while saving. Retry the reference."); return;
        }
        ReadyDetector.Result state = detector.detect(screen,request.profile);
        if (!manual && (state.state() != ReadyDetector.State.READY || state.kind() != kind)) return;
        if (manual && (!screen.connected() || !screen.commandRunning() || state.state() == ReadyDetector.State.CONFIRMATION || state.state() == ReadyDetector.State.EMPTY_SHELL)) {
            stop(request,"The Custom agent input could not be confirmed. No reference was sent."); return;
        }
        String text = request.reference.format(request.session.directory(),kind);
        // Remove before writing: ambiguous failures must not retry automatically.
        queue.remove(request);
        try { request.session.insert(" " + text + " "); request.session.focus(); }
        catch (RuntimeException | LinkageError ex) { AgentNotifications.show(project,"Insertion result is uncertain. Check Terminal before retrying. " + ex.getMessage(),text,request.retry); }
        finally { release(request); }
    }
    private String copy(Request request) {
        try { return request.reference == null ? null : request.reference.format(null,AgentProfile.Kind.Custom); }
        catch (RuntimeException ex) { return null; }
    }
    private void stop(Request request, String reason) {
        if (!queue.contains(request)) return;
        String text = copy(request); finish(request);
        if (enabled()) AgentNotifications.show(project,reason == null ? "Terminal access failed; automatic insertion stopped." : reason,text,request.retry);
    }
    private void report(Throwable ex, EditorReference reference, Runnable retry) {
        String text = null;
        try { if (reference != null) text = reference.format(null,AgentProfile.Kind.Custom); } catch (RuntimeException ignored) { }
        AgentNotifications.show(project,ex.getMessage() == null ? "Terminal access failed. No further input will be sent." : ex.getMessage(),text,retry);
    }
    private void finish(Request request) { queue.remove(request); release(request); }
    private void release(Request request) {
        if (request.reference != null) request.reference.close();
        if (request.notice != null) request.notice.expire();
        if (request.watch != null) try { request.watch.close(); } catch (Exception ignored) { }
    }
    public void cancelAll() {
        alarm.cancelAllRequests(); pollScheduled = false;
        for (var entry : queue.entries()) finish(entry.value());
    }
    @Override public void dispose() { disposed = true; cancelAll(); profileTabs.clear(); launchedProfiles.clear(); }
}
