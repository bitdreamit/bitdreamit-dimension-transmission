package com.bitdreamit.connect.plugins.transmission.dimension.client;

import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionConstants;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;

/**
 * Modal settings dialog for the Siemens Dimension transmission mode.
 *
 * <p>Loads every field from the {@link DimensionTransmissionModeProperties}
 * reference passed to the constructor and writes the edited values back
 * into the SAME reference when OK is pressed, so Mirth's channel
 * serialization picks the changes up automatically.</p>
 */
public class DimensionSettingsDialog extends JDialog {

    private final DimensionTransmissionModeProperties props;
    private boolean okPressed = false;

    // --- frame bytes ---
    private JTextField startOfFrameField;
    private JTextField endOfFrameField;
    private JTextField fieldSeparatorField;
    private JTextField enquiryField;

    // --- handshake ---
    private JCheckBox useChecksumCheck;
    private JTextField checksumLengthField;
    private JTextField ackField;
    private JTextField nakField;
    private JTextField maxRetransField;
    private JTextField ackTimeoutField;
    private JTextField frameTimeoutField;

    // --- auto responses ---
    private JCheckBox autoResultAcceptanceCheck;
    private JTextField resultAcceptanceStatusField;
    private JCheckBox autoPollResponseCheck;
    private JCheckBox autoEnqAckCheck;

    // --- dispatch / mode ---
    private JCheckBox includeChecksumCheck;
    private JComboBox<String> outputFormatCombo;
    private JCheckBox serverModeCheck;

    public DimensionSettingsDialog(Frame owner, DimensionTransmissionModeProperties props) {
        super(owner, "Siemens Dimension Frame Settings", Dialog.ModalityType.APPLICATION_MODAL);
        this.props = props;
        initComponents();
        loadFromProps();
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(owner);
    }

    private void initComponents() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setContentPane(content);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        content.add(buildFramePanel(), gbc);
        gbc.gridy++;
        content.add(buildHandshakePanel(), gbc);
        gbc.gridy++;
        content.add(buildAutoResponsePanel(), gbc);
        gbc.gridy++;
        content.add(buildDispatchPanel(), gbc);
        gbc.gridy++;
        content.add(buildButtonPanel(), gbc);
    }

    private JPanel buildFramePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Frame Bytes"));
        startOfFrameField  = addHexRow(panel, 0, "Start of Frame (STX)",       "0x02 - Start of Transmission");
        endOfFrameField    = addHexRow(panel, 1, "End of Frame (ETX)",         "0x03 - End of Transmission");
        fieldSeparatorField= addHexRow(panel, 2, "Field Separator (FS)",       "0x1C - between every data field");
        enquiryField       = addHexRow(panel, 3, "Enquiry (ENQ)",              "0x05 - retry request from receiver");
        return panel;
    }

    private JPanel buildHandshakePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Data-Link Handshake"));

        useChecksumCheck   = addBooleanRow(panel, 0, "Use Checksum", "Validate the 8-bit Add-Mod-256 checksum of every frame");
        checksumLengthField= addIntRow(panel, 1, "Checksum Byte Length", "Number of ASCII-hex checksum characters (2)");
        ackField           = addHexRow(panel, 2, "Positive Acknowledge (ACK)", "0x06 - sent after every valid frame");
        nakField           = addHexRow(panel, 3, "Negative Acknowledge (NAK)", "0x15 - sent on checksum mismatch");
        maxRetransField    = addIntRow(panel, 4, "Max Retransmissions", "NAK retries before aborting (protocol allows 4)");
        ackTimeoutField    = addIntRow(panel, 5, "ACK Timeout (ms)", "Instrument ACK/NAK timer is 1000 ms");
        frameTimeoutField  = addIntRow(panel, 6, "Frame Timeout (ms)", "Wait for a complete frame once STX was received");
        return panel;
    }

    private JPanel buildAutoResponsePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Application-Level Auto Responses"));

        autoResultAcceptanceCheck = addBooleanRow(panel, 0, "Auto Result Acceptance",
                "Answer R/C frames with <STX>M<FS>A<FS><FS>E2<ETX> right after the ACK");
        resultAcceptanceStatusField = addTextRow(panel, 1, "Result Acceptance Status",
                "A = accept, R = reject (reason 1)");
        autoPollResponseCheck = addBooleanRow(panel, 2, "Auto Poll/Query Response",
                "Answer P/I frames with <STX>N<FS>6A<ETX> (No Request). Turn off for order download");
        autoEnqAckCheck = addBooleanRow(panel, 3, "Auto ENQ Acknowledge",
                "Answer a stray ENQ from the instrument with ACK");
        return panel;
    }

    private JPanel buildDispatchPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Dispatch / Mode"));

        includeChecksumCheck = addBooleanRow(panel, 0, "Checksum in Payload",
                "Keep the 2-character checksum at the end of the dispatched message (RAW_FRAME output only)");

        GridBagConstraints fGbc = new GridBagConstraints();
        fGbc.insets = new Insets(2, 5, 2, 5);
        fGbc.anchor = GridBagConstraints.WEST;
        JLabel fmtLabel = new JLabel("Message Output Format:");
        fGbc.gridx = 0;
        fGbc.gridy = 1;
        panel.add(fmtLabel, fGbc);
        outputFormatCombo = new JComboBox<String>(new String[] {
                com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionHL7Format.RAW_FRAME,
                com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionHL7Format.HL7_V2 });
        outputFormatCombo.setToolTipText(
                "RAW_FRAME = raw frame payload (transformer parses; Raw inbound data type). "
                + "HL7_V2 = plugin converts every frame to standard HL7 v2.x (HL7 V2.x inbound data type; "
                + "transformer reads msg['OBX']... like an ASTM/HL7 channel).");
        fGbc.gridx = 1;
        fGbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(outputFormatCombo, fGbc);

        serverModeCheck = addBooleanRow(panel, 2, "Server Mode",
                "true = receiver (listener/source), false = sender");
        return panel;
    }

    private JPanel buildButtonPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));

        JButton okButton = new JButton("OK");
        okButton.setMnemonic('O');
        okButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (saveToProps()) {
                    okPressed = true;
                    dispose();
                }
            }
        });

        JButton cancelButton = new JButton("Cancel");
        cancelButton.setMnemonic('C');
        cancelButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });

        panel.add(Box.createHorizontalGlue());
        panel.add(okButton);
        panel.add(Box.createRigidArea(new Dimension(5, 0)));
        panel.add(cancelButton);
        panel.add(Box.createHorizontalGlue());

        getRootPane().setDefaultButton(okButton);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });
        return panel;
    }

    // ------------------------------------------------------------------
    // Row builders
    // ------------------------------------------------------------------

    private JTextField addHexRow(JPanel panel, int row, String label, String tip) {
        JTextField field = addTextRow(panel, row, label, tip);
        return field;
    }

    private JTextField addIntRow(JPanel panel, int row, String label, String tip) {
        return addTextRow(panel, row, label, tip);
    }

    private JTextField addTextRow(JPanel panel, int row, String label, String tip) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;

        JLabel lbl = new JLabel(label + ":");
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(lbl, gbc);

        JTextField field = new JTextField(10);
        field.setToolTipText(tip);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gbc);
        return field;
    }

    private JCheckBox addBooleanRow(JPanel panel, int row, String label, String tip) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;

        JCheckBox check = new JCheckBox(label);
        check.setToolTipText(tip);
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        panel.add(check, gbc);
        return check;
    }

    // ------------------------------------------------------------------
    // Load / save
    // ------------------------------------------------------------------

    private void loadFromProps() {
        startOfFrameField.setText(hex(props.getStartOfFrameByte()));
        endOfFrameField.setText(hex(props.getEndOfFrameByte()));
        fieldSeparatorField.setText(hex(props.getFieldSeparatorByte()));
        enquiryField.setText(hex(props.getEnquiryByte()));

        useChecksumCheck.setSelected(props.isUseChecksum());
        checksumLengthField.setText(String.valueOf(props.getChecksumByteLength()));
        ackField.setText(hex(props.getPositiveAckByte()));
        nakField.setText(hex(props.getNegativeAckByte()));
        maxRetransField.setText(String.valueOf(props.getMaxRetransmissions()));
        ackTimeoutField.setText(String.valueOf(props.getAckTimeoutMs()));
        frameTimeoutField.setText(String.valueOf(props.getFrameTimeoutMs()));

        autoResultAcceptanceCheck.setSelected(props.isAutoResultAcceptance());
        resultAcceptanceStatusField.setText(props.getResultAcceptanceStatus());
        autoPollResponseCheck.setSelected(props.isAutoPollResponse());
        autoEnqAckCheck.setSelected(props.isAutoEnqAck());

        includeChecksumCheck.setSelected(props.isIncludeChecksumInPayload());
        outputFormatCombo.setSelectedItem(props.getMessageOutputFormat());
        serverModeCheck.setSelected(props.isServerMode());
    }

    private boolean saveToProps() {
        try {
            props.setStartOfFrameByte(parseHex(startOfFrameField.getText(), DimensionConstants.STX));
            props.setEndOfFrameByte(parseHex(endOfFrameField.getText(), DimensionConstants.ETX));
            props.setFieldSeparatorByte(parseHex(fieldSeparatorField.getText(), DimensionConstants.FS));
            props.setEnquiryByte(parseHex(enquiryField.getText(), DimensionConstants.ENQ));

            props.setUseChecksum(useChecksumCheck.isSelected());
            props.setChecksumByteLength(parseInt(checksumLengthField.getText(), 2));
            props.setPositiveAckByte(parseHex(ackField.getText(), DimensionConstants.ACK));
            props.setNegativeAckByte(parseHex(nakField.getText(), DimensionConstants.NAK));
            props.setMaxRetransmissions(parseInt(maxRetransField.getText(), DimensionConstants.DEFAULT_MAX_RETRANSMISSIONS));
            props.setAckTimeoutMs(parseInt(ackTimeoutField.getText(), DimensionConstants.DEFAULT_ACK_TIMEOUT_MS));
            props.setFrameTimeoutMs(parseInt(frameTimeoutField.getText(), DimensionConstants.DEFAULT_FRAME_TIMEOUT_MS));

            props.setAutoResultAcceptance(autoResultAcceptanceCheck.isSelected());
            props.setResultAcceptanceStatus(resultAcceptanceStatusField.getText().trim().isEmpty()
                    ? "A" : resultAcceptanceStatusField.getText().trim().substring(0, 1).toUpperCase());
            props.setAutoPollResponse(autoPollResponseCheck.isSelected());
            props.setAutoEnqAck(autoEnqAckCheck.isSelected());

            props.setIncludeChecksumInPayload(includeChecksumCheck.isSelected());
            props.setMessageOutputFormat(outputFormatCombo.getSelectedItem() == null
                    ? com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionHL7Format.RAW_FRAME
                    : String.valueOf(outputFormatCombo.getSelectedItem()));
            props.setServerMode(serverModeCheck.isSelected());
            return true;
        } catch (Exception e) {
            JLabel message = new JLabel("Invalid value: " + e.getMessage());
            javax.swing.JOptionPane.showMessageDialog(this, message,
                    "Invalid Settings", javax.swing.JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static String hex(int b) {
        return String.format("0x%02X", b & 0xFF);
    }

    private static int parseHex(String s, int defaultValue) {
        try {
            String v = s.trim().replace("0x", "").replace("0X", "");
            return v.isEmpty() ? defaultValue : (Integer.parseInt(v, 16) & 0xFF);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a valid hex byte");
        }
    }

    private static int parseInt(String s, int defaultValue) {
        try {
            return s.trim().isEmpty() ? defaultValue : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a valid number");
        }
    }

    public boolean isOkPressed() {
        return okPressed;
    }
}
