package io.snakeorcamel.agentterminal;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.BorderLayout;
import java.util.List;

/** Shared profile chooser; cancellation never changes profile settings. */
final class AgentProfileDialog extends DialogWrapper {
    private final ComboBox<AgentProfile> profiles;
    private final String message;

    AgentProfileDialog(Project project, List<AgentProfile> choices, String title, String message) {
        super(project);
        this.profiles = new ComboBox<>(choices.toArray(AgentProfile[]::new));
        this.message = message;
        setTitle(title);
        init();
        setOKActionEnabled(!choices.isEmpty());
    }

    static @Nullable AgentProfile choose(Project project, List<AgentProfile> choices, String title, String message) {
        var dialog = new AgentProfileDialog(project, choices, title, message);
        return dialog.showAndGet() ? dialog.selectedProfile() : null;
    }

    @Nullable AgentProfile selectedProfile() {
        int index = profiles.getSelectedIndex();
        return index < 0 ? null : profiles.getItemAt(index);
    }

    @Override protected JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout(0, 8));
        var label = new JLabel(message);
        label.setLabelFor(profiles);
        panel.add(label, BorderLayout.NORTH);
        panel.add(profiles, BorderLayout.CENTER);
        return panel;
    }

    @Override public JComponent getPreferredFocusedComponent() { return profiles; }
}
