package io.snakeorcamel.agentterminal;

import com.intellij.notification.*;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import java.awt.datatransfer.StringSelection;

final class AgentNotifications {
    private AgentNotifications() {}
    static Notification show(Project project, String message, String reference, Runnable retry) {
        return show(project,message,reference,retry,null);
    }
    static Notification show(Project project, String message, String reference, Runnable retry, Runnable manual) {
        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup("SnakeOrCamel Agent Terminal")
                .createNotification("Agent Terminal", message, NotificationType.INFORMATION);
        if (reference != null) notification.addAction(NotificationAction.createSimple("Copy reference",
                () -> CopyPasteManager.getInstance().setContents(new StringSelection(reference))));
        if (retry != null) notification.addAction(NotificationAction.createSimple("Retry", () -> { notification.expire(); retry.run(); }));
        if (manual != null) notification.addAction(NotificationAction.createSimple("Insert after checking input…",manual));
        notification.notify(project);
        return notification;
    }
}
