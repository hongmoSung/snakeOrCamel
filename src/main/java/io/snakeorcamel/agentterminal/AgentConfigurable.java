package io.snakeorcamel.agentterminal;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;

public final class AgentConfigurable implements SearchableConfigurable {
    private JCheckBox enabled;
    private JPanel panel;
    @Override public @NotNull String getId() { return "SnakeOrCamel.AgentTerminal"; }
    @Override public String getDisplayName() { return "Agent Terminal"; }
    @Override public JComponent createComponent() {
        panel = new JPanel(new BorderLayout(0, 12));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        enabled = new JCheckBox("Enable Agent Terminal integration (all projects)");
        top.add(new JLabel("<html><b>References always target the first visible Terminal tab.</b><br>"
                + "A reference request may launch the default agent in an empty shell and wait up to 30 seconds.<br>"
                + "References are inserted without Enter; existing questions are preserved.<br>"
                + "Enabling or opening a project never launches an agent.<br>"
                + "Other plugins may separately share editor context automatically.<br>"
                + "CLI screen changes may prevent readiness detection; uncertain input is held.</html>"));
        top.add(Box.createVerticalStrut(12)); top.add(enabled);
        for (String[] plugin : new String[][]{{"Terminal", "org.jetbrains.plugins.terminal"},
                {"Claude Code", "com.anthropic.code.plugin"}, {"Codex CLI", "com.github.codexjb"}}) {
            top.add(new JLabel(plugin[0] + ": " + pluginStatus(plugin[1])));
        }
        JTextArea status = new JTextArea(AgentKeymap.getInstance().status());
        status.setEditable(false); status.setLineWrap(true); status.setWrapStyleWord(true); status.setOpaque(false);
        top.add(status);
        JButton keymap = new JButton("Open Keymap Settings…");
        keymap.addActionListener(e -> com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(null, "Keymap"));
        top.add(keymap);
        panel.add(top, BorderLayout.NORTH);
        reset(); return panel;
    }
    static String pluginStatus(String id) {
        var descriptor = PluginManagerCore.getPlugin(PluginId.getId(id));
        return descriptor == null ? "not installed" : descriptor.isEnabled() ? "installed, enabled" : "installed, disabled";
    }
    @Override public boolean isModified() { return enabled != null && enabled.isSelected() != AgentSettings.getInstance().isEnabled(); }
    @Override public void apply() {
        AgentSettings.getInstance().getState().enabled = enabled.isSelected();
        AgentKeymap.getInstance().reconcile();
    }
    @Override public void reset() { if (enabled != null) enabled.setSelected(AgentSettings.getInstance().isEnabled()); }
    @Override public void disposeUIResources() { panel = null; enabled = null; }

    public static final class Parent implements SearchableConfigurable {
        @Override public @NotNull String getId() { return "SnakeOrCamel"; }
        @Override public String getDisplayName() { return "SnakeOrCamel"; }
        @Override public JComponent createComponent() { return new JLabel("Configure Agent Terminal in the child page."); }
        @Override public boolean isModified() { return false; }
        @Override public void apply() {}
    }
}
