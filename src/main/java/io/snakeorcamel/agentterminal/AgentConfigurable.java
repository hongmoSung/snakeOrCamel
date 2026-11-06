package io.snakeorcamel.agentterminal;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;

public final class AgentConfigurable implements SearchableConfigurable {
    private final Project project;
    private DefaultListModel<AgentProfile> profiles;
    private JList<AgentProfile> profileList;
    private JComboBox<AgentProfile> defaultProfile;
    private JCheckBox enabled;
    private JBLabel status;
    public AgentConfigurable(Project project) { this.project = project; }
    private JPanel panel;
    @Override public @NotNull String getId() { return "SnakeOrCamel.AgentTerminal"; }
    @Override public String getDisplayName() { return "Agent Terminal"; }
    @Override public JComponent createComponent() {
        panel = new JPanel(new BorderLayout(0, 12));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        enabled = new JCheckBox("Enable Agent Terminal integration (all projects)");
        top.add(new JLabel("<html><b>References target a dedicated tab for the default agent profile.</b><br>"
                + "Run Agent and the reference shortcut share the same tab and startup.<br>"
                + "Tab order, renaming and moves between Terminal splits preserve the target.<br>"
                + "After an agent exits, a new dedicated tab is created; the old shell is preserved.<br>"
                + "References preserve existing questions and are inserted without Enter.<br>"
                + "Startup waits at least two seconds and confirms a running process (up to 30 seconds).<br>"
                + "Unknown process state waits without sending input or launching another agent.<br>"
                + "Enabling or opening a project never launches an agent. Restored tabs are not adopted.<br>"
                + "Other plugins may separately share editor context automatically.<br>"
                + "Startup confirmation does not detect login or permission prompts.</html>"));
        top.add(Box.createVerticalStrut(12)); top.add(enabled);
        for (String[] plugin : new String[][]{{"Terminal", "org.jetbrains.plugins.terminal"},
                {"Claude Code", "com.anthropic.code.plugin"}, {"Codex CLI", "com.github.codexjb"}}) {
            top.add(new JLabel(plugin[0] + ": " + pluginStatus(plugin[1])));
        }
        status = new JBLabel(AgentKeymap.getInstance().status()).setAllowAutoWrapping(true).setCopyable(true);
        top.add(status);
        JButton keymap = new JButton("Open Keymap Settings…");
        keymap.addActionListener(e -> com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(null, "Keymap"));
        top.add(keymap);
        // BoxLayout combines each child's cross-axis alignment. A centered status component otherwise
        // pushes the left-aligned labels and checkbox towards the middle of a wide settings page.
        for (Component component : top.getComponents()) {
            if (component instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        panel.add(top, BorderLayout.NORTH);
        panel.add(profilePanel(), BorderLayout.CENTER);
        reset(); return panel;
    }
    static String pluginStatus(String id) {
        PluginId plugin = PluginId.getId(id);
        return !PluginManagerCore.isPluginInstalled(plugin) ? "not installed"
                : PluginManagerCore.isDisabled(plugin) ? "installed, disabled" : "installed, enabled";
    }
    private JComponent profilePanel() {
        JPanel area = new JPanel(new BorderLayout(8, 8));
        area.setBorder(BorderFactory.createTitledBorder("Project-local launch profiles (not shared)"));
        profiles = new DefaultListModel<>(); profileList = new JList<>(profiles);
        area.add(new JScrollPane(profileList), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (String label : new String[]{"Add…", "Edit…", "Delete"}) {
            JButton button = new JButton(label); buttons.add(button);
            button.addActionListener(e -> {
                int index = profileList.getSelectedIndex();
                if (label.equals("Delete")) {
                    if (index >= 0) { profiles.remove(index); refreshDefault(); }
                } else {
                    if (label.equals("Edit…") && index < 0) return;
                    AgentProfile edited = editProfile(label.equals("Add…") ? new AgentProfile() : profiles.get(index).copy());
                    if (edited != null) {
                        if (label.equals("Add…")) profiles.addElement(edited); else profiles.set(index, edited);
                        refreshDefault();
                    }
                }
            });
        }
        defaultProfile = new JComboBox<>();
        buttons.add(new JLabel("Default:")); buttons.add(defaultProfile);
        area.add(buttons, BorderLayout.SOUTH);
        return area;
    }
    private AgentProfile editProfile(AgentProfile profile) {
        JTextField name = new JTextField(profile.name, 32);
        JComboBox<AgentProfile.Kind> kind = new JComboBox<>(AgentProfile.Kind.values()); kind.setSelectedItem(profile.kind);
        JTextField command = new JTextField(profile.command, 32);
        JTextField directory = new JTextField(profile.directory, 32);
        JTextField pattern = new JTextField(profile.readyPattern, 32);
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Name")); form.add(name); form.add(new JLabel("Kind")); form.add(kind);
        form.add(new JLabel("Shell command (executed verbatim)")); form.add(command);
        form.add(new JLabel("Working directory (blank = project root)")); form.add(directory);
        form.add(new JLabel("Custom ready text on cursor line (optional)")); form.add(pattern);
        while (JOptionPane.showConfirmDialog(panel, form, "Agent Profile", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            profile.name = name.getText().trim(); profile.kind = (AgentProfile.Kind) kind.getSelectedItem();
            profile.command = command.getText(); profile.directory = directory.getText().trim(); profile.readyPattern = pattern.getText();
            try { profile.validate(); return profile; }
            catch (IllegalArgumentException ex) { Messages.showErrorDialog(project, ex.getMessage(), "Agent Profile"); }
        }
        return null;
    }
    private List<AgentProfile> editedProfiles() { return java.util.Collections.list(profiles.elements()); }
    private String selectedDefault() {
        AgentProfile selected = (AgentProfile) defaultProfile.getSelectedItem();
        return selected == null ? "" : selected.id;
    }
    private void refreshDefault() {
        String id = selectedDefault(); defaultProfile.removeAllItems(); defaultProfile.addItem(null);
        for (AgentProfile profile : editedProfiles()) {
            defaultProfile.addItem(profile);
            if (profile.id.equals(id)) defaultProfile.setSelectedItem(profile);
        }
    }
    @Override public boolean isModified() {
        return enabled != null && (enabled.isSelected() != AgentSettings.getInstance().isEnabled()
                || !editedProfiles().equals(AgentProfiles.getInstance(project).profiles())
                || !selectedDefault().equals(AgentProfiles.getInstance(project).getState().defaultId));
    }
    @Override public void apply() throws ConfigurationException {
        try { for (AgentProfile profile : editedProfiles()) profile.validate(); }
        catch (IllegalArgumentException ex) { throw new ConfigurationException(ex.getMessage()); }
        AgentProfiles.getInstance(project).getState().profiles = new ArrayList<>(editedProfiles().stream().map(AgentProfile::copy).toList());
        AgentProfiles.getInstance(project).getState().defaultId = selectedDefault();
        AgentKeymap.getInstance().applyEnabled(enabled.isSelected());
        status.setText(AgentKeymap.getInstance().status());
    }
    @Override public void reset() {
        if (enabled == null) return;
        enabled.setSelected(AgentSettings.getInstance().isEnabled());
        status.setText(AgentKeymap.getInstance().status());
        profiles.clear(); AgentProfiles.getInstance(project).profiles().forEach(profiles::addElement);
        refreshDefault(); defaultProfile.setSelectedItem(null);
        String id = AgentProfiles.getInstance(project).getState().defaultId;
        for (AgentProfile profile : editedProfiles()) if (profile.id.equals(id)) defaultProfile.setSelectedItem(profile);
    }
    @Override public void disposeUIResources() { panel = null; status = null; enabled = null; profiles = null; profileList = null; defaultProfile = null; }

    public static final class Parent implements SearchableConfigurable {
        @Override public @NotNull String getId() { return "SnakeOrCamel"; }
        @Override public String getDisplayName() { return "SnakeOrCamel"; }
        @Override public JComponent createComponent() { return new JLabel("Configure Agent Terminal in the child page."); }
        @Override public boolean isModified() { return false; }
        @Override public void apply() {}
    }
}
