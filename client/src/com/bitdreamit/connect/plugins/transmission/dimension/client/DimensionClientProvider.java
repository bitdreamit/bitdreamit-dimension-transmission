package com.bitdreamit.connect.plugins.transmission.dimension.client;

import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionConstants;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;
import com.mirth.connect.model.transmission.TransmissionModeProperties;
import com.mirth.connect.plugins.TransmissionModeClientProvider;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Client-side provider for the Siemens Dimension transmission mode.
 *
 * <p>Mirrors Mirth's {@code MLLPModeClientProvider}: returns a small
 * {@link DimensionSettingsPanel} (a wrench-icon button) from
 * {@link #getSettingsComponent()}. When the user clicks the button,
 * the {@link DimensionSettingsDialog} modal dialog opens.</p>
 *
 * <p><b>Save wiring:</b> the dialog takes the provider's current
 * {@code props} reference in its constructor, loads every field from it
 * on open, and writes the field values back into the SAME {@code props}
 * object on OK. Because Mirth holds the same reference (it called
 * {@link #setProperties(TransmissionModeProperties)} earlier), Mirth's
 * channel serialization picks up the changes automatically.</p>
 */
public class DimensionClientProvider extends TransmissionModeClientProvider {

    private DimensionTransmissionModeProperties props;
    private DimensionSettingsPanel settingsPanel;
    private Frame parentFrame;

    @Override
    public String getSampleLabel() {
        return "Siemens Dimension Sample";
    }

    /**
     * A real Result (R) message with a VALID Add-Mod-256 checksum (0x8D),
     * so "Send Sample" / test transmissions validate end-to-end.
     * Field layout per Table 1-22 of PN D00396:
     * R | 0 | PatientID | Sample# | 1 | Location | 0 | ssmmhhddmmyy | 1 | 1 | 2 |
     * GLU | 85.00 | mg/dL | (err) | BUN | 7.00 | mg/dL | (err) | CHK
     */
    @Override
    public String getSampleValue() {
        return "R\u001C0\u001C\u001C043092005\u001C1\u001C\u001C0\u001C174513190302\u001C"
             + "1\u001C1\u001C2\u001CGLU\u001C85.00\u001Cmg/dL\u001C\u001CBUN\u001C7.00"
             + "\u001Cmg/dL\u001C\u001C8D";
    }

    @Override
    public TransmissionModeProperties getProperties() {
        ensureProps();
        return props;
    }

    @Override
    public TransmissionModeProperties getDefaultProperties() {
        return new DimensionTransmissionModeProperties();
    }

    @Override
    public void setProperties(TransmissionModeProperties properties) {
        if (properties == null) {
            this.props = new DimensionTransmissionModeProperties();
        } else if (!(properties instanceof DimensionTransmissionModeProperties)) {
            throw new IllegalArgumentException(
                "Expected DimensionTransmissionModeProperties but got: "
                + properties.getClass().getName());
        } else {
            this.props = (DimensionTransmissionModeProperties) properties;
        }
    }

    @Override
    public boolean checkProperties(TransmissionModeProperties properties, boolean highlight) {
        if (properties == null) return false;
        if (!(properties instanceof DimensionTransmissionModeProperties)) return false;
        DimensionTransmissionModeProperties p = (DimensionTransmissionModeProperties) properties;
        if (p.getAckTimeoutMs() <= 0) return false;
        if (p.getFrameTimeoutMs() <= 0) return false;
        if (p.getMaxRetransmissions() < 1) return false;
        if (p.getChecksumByteLength() < 1 || p.getChecksumByteLength() > 2) return false;
        return true;
    }

    @Override
    public void resetInvalidProperties() {
        ensureProps();
        if (props.getAckTimeoutMs() <= 0)
            props.setAckTimeoutMs(DimensionConstants.DEFAULT_ACK_TIMEOUT_MS);
        if (props.getFrameTimeoutMs() <= 0)
            props.setFrameTimeoutMs(DimensionConstants.DEFAULT_FRAME_TIMEOUT_MS);
        if (props.getMaxRetransmissions() < 1)
            props.setMaxRetransmissions(DimensionConstants.DEFAULT_MAX_RETRANSMISSIONS);
        if (props.getChecksumByteLength() < 1 || props.getChecksumByteLength() > 2)
            props.setChecksumByteLength(2);
    }

    /**
     * Returns a small panel with a "Frame Settings" (wrench icon) button.
     * Clicking the button opens the {@link DimensionSettingsDialog} modal.
     */
    @Override
    public JComponent getSettingsComponent() {
        if (settingsPanel == null) {
            settingsPanel = new DimensionSettingsPanel();
            settingsPanel.setActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    openSettingsDialog();
                }
            });
        }
        return settingsPanel;
    }

    private void openSettingsDialog() {
        ensureProps();

        // Find the parent frame by walking up the component hierarchy
        Frame frame = parentFrame;
        if (frame == null && settingsPanel != null) {
            Component c = settingsPanel;
            while (c != null && !(c instanceof Frame)) {
                c = c.getParent();
            }
            if (c instanceof Frame) {
                frame = (Frame) c;
            }
        }

        // Construct the dialog with the CURRENT props reference.
        // The dialog loads from props on open, writes back to props on OK.
        DimensionSettingsDialog dialog = new DimensionSettingsDialog(frame, props);
        dialog.setVisible(true);

        if (dialog.isOkPressed()) {
            checkProperties(props, false);
        }
    }

    public void setParentFrame(Frame parent) {
        this.parentFrame = parent;
    }

    private void ensureProps() {
        if (props == null) {
            props = new DimensionTransmissionModeProperties();
        }
    }
}
