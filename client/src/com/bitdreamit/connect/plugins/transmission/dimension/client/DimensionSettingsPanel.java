package com.bitdreamit.connect.plugins.transmission.dimension.client;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionListener;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.GroupLayout;
import javax.swing.JPanel;

/**
 * Small settings panel with a wrench icon button.
 *
 * <p>Mirrors Mirth's MLLPModeSettingsPanel: a small panel containing
 * just a wrench icon button. When the user clicks the button, the
 * {@link DimensionSettingsDialog} modal dialog opens. The panel is
 * displayed inline in the channel editor next to the
 * "Transmission Mode" dropdown.</p>
 */
public class DimensionSettingsPanel extends JPanel {

    private JButton settingsButton;
    private ActionListener actionListener;

    public DimensionSettingsPanel() {
        initComponents();
    }

    private void initComponents() {
        // Load the wrench icon from Mirth's built-in images
        ImageIcon wrenchIcon = null;
        try {
            java.net.URL iconUrl = getClass().getClassLoader()
                .getResource("com/mirth/connect/client/ui/images/wrench.png");
            if (iconUrl != null) {
                wrenchIcon = new ImageIcon(iconUrl);
            }
        } catch (Exception e) {
            // Icon not found - text fallback is used
        }

        settingsButton = new JButton();
        if (wrenchIcon != null) {
            settingsButton.setIcon(wrenchIcon);
        } else {
            settingsButton.setText("Dimension Settings");
        }

        settingsButton.setMargin(new Insets(0, 0, 0, 0));
        settingsButton.setFocusable(false);
        settingsButton.setBorderPainted(false);
        settingsButton.setContentAreaFilled(false);
        settingsButton.setOpaque(false);
        settingsButton.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        settingsButton.setToolTipText("Siemens Dimension frame settings");

        GroupLayout layout = new GroupLayout(this);
        setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(GroupLayout.Alignment.CENTER)
            .addComponent(settingsButton)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(GroupLayout.Alignment.CENTER)
            .addComponent(settingsButton)
        );

        settingsButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (actionListener != null) {
                    actionListener.actionPerformed(e);
                }
            }
        });
    }

    public void setActionListener(ActionListener listener) {
        this.actionListener = listener;
    }

    public JButton getSettingsButton() {
        return settingsButton;
    }
}
