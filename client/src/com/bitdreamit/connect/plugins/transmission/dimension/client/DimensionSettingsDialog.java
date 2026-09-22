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
 * <p>Follows Mirth Connect's own transmission-mode settings pattern
 * ({@code TransmissionModeClientProvider} contract): the provider hands this
 * dialog the SAME {@link DimensionTransmissionModeProperties} reference that
 * Mirth holds inside its editable channel working-copy. The dialog loads
 * every control from that reference on open and writes the edited values
 * back into it when OK is pressed - so Mirth's channel serialization picks
 * the changes up automatically when the user saves the channel.</p>
 *
 * <p><b>Every</b> property of {@code DimensionTransmissionModeProperties}
 * has a control here (frame bytes, handshake, auto responses, order
 * download, dispatch/mode) - nothing is UI-only, and no property is left
 * without a form field.</p>
 *
 * <p><b>Commit discipline (Mirth-style, all-or-nothing):</b> Save validates
 * and parses EVERY control into locals first; only if all values are valid
 * are they committed to the properties object in one pass. An invalid value
 * can never leave a half-written properties object behind.</p>
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

    // --- dynamic order download (bidirectional) ---
    private JCheckBox orderLookupCheck;
    private JTextField orderQueueKeyField;

    // --- dispatch / mode ---
    private JCheckBox includeChecksumCheck;
    private JComboBox<String> outputFormatCombo;
    private JCheckBox serverModeCheck;

    // --- diagnostics ---
    private JCheckBox wireDebugCheck;

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
        content.add(buildOrderDownloadPanel(), gbc);
        gbc.gridy++;
        content.add(buildDispatchPanel(), gbc);
        gbc.gridy++;
        content.add(buildDebugPanel(), gbc);
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

    private JPanel buildOrderDownloadPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Order Download (Bidirectional)"));

        orderLookupCheck = addBooleanRow(panel, 0, "Dynamic Order Lookup",
                "Answer P/I frames with a Sample Request (D) built from the DimensionOrderRegistry "
                + "(orders pushed via Reg.pushOrder / DB feeder). Turn off when the channel transformer "
                + "builds protocol frames itself.");
        orderQueueKeyField = addTextRow(panel, 1, "Order Queue Key",
                "Registry queue key - pushing channels must push with the same key (empty = default)");
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

    private JPanel buildDebugPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Debug"));

        wireDebugCheck = addBooleanRow(panel, 0, "Wire Debug (raw frames to log)",
                "Log EVERY raw frame the analyzer sends/receives (hex + ASCII), every ACK/NAK/ENQ "
                + "control byte, the internal raw-to-HL7 conversion pair and the registry decisions "
                + "to the Mirth server log (logger 'dimension.wire', INFO level). "
                + "Global override without redeploy: -Ddimension.wireDebug=true");
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

        orderLookupCheck.setSelected(props.isOrderLookupEnabled());
        orderQueueKeyField.setText(props.getOrderQueueKey());

        includeChecksumCheck.setSelected(props.isIncludeChecksumInPayload());
        outputFormatCombo.setSelectedItem(props.getMessageOutputFormat());
        serverModeCheck.setSelected(props.isServerMode());
        wireDebugCheck.setSelected(props.isWireDebugEnabled());
    }

    /**
     * Mirth-style all-or-nothing commit: parse and validate EVERY control
     * into locals first; only if all values are valid are they written to
     * the properties object in one pass. A bad value anywhere leaves the
     * properties object completely untouched.
     */
    private boolean saveToProps() {
        // --- pass 1: validate everything into locals ---------------------
        int startOfFrame, endOfFrame, fieldSep, enquiry, ack, nak;
        int checksumLen, maxRetrans, ackTimeout, frameTimeout;
        String resultAccStatus, outputFormat, orderQueueKey;
        try {
            startOfFrame = parseHex(startOfFrameField.getText(), DimensionConstants.STX);
            endOfFrame   = parseHex(endOfFrameField.getText(), DimensionConstants.ETX);
            fieldSep     = parseHex(fieldSeparatorField.getText(), DimensionConstants.FS);
            enquiry      = parseHex(enquiryField.getText(), DimensionConstants.ENQ);
            ack          = parseHex(ackField.getText(), DimensionConstants.ACK);
            nak          = parseHex(nakField.getText(), DimensionConstants.NAK);

            checksumLen  = parseInt(checksumLengthField.getText(), 2);
            maxRetrans   = parseInt(maxRetransField.getText(), DimensionConstants.DEFAULT_MAX_RETRANSMISSIONS);
            ackTimeout   = parseInt(ackTimeoutField.getText(), DimensionConstants.DEFAULT_ACK_TIMEOUT_MS);
            frameTimeout = parseInt(frameTimeoutField.getText(), DimensionConstants.DEFAULT_FRAME_TIMEOUT_MS);

            String ras = resultAcceptanceStatusField.getText().trim();
            resultAccStatus = ras.isEmpty() ? "A" : ras.substring(0, 1).toUpperCase();

            outputFormat = outputFormatCombo.getSelectedItem() == null
                    ? com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionHL7Format.RAW_FRAME
                    : String.valueOf(outputFormatCombo.getSelectedItem());

            String oqk = orderQueueKeyField.getText().trim();
            orderQueueKey = oqk.isEmpty() ? "default" : oqk;
        } catch (Exception e) {
            JLabel message = new JLabel("Invalid value: " + e.getMessage());
            javax.swing.JOptionPane.showMessageDialog(this, message,
                    "Invalid Settings", javax.swing.JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // --- pass 2: commit to the Mirth-owned properties object ---------
        props.setStartOfFrameByte(startOfFrame);
        props.setEndOfFrameByte(endOfFrame);
        props.setFieldSeparatorByte(fieldSep);
        props.setEnquiryByte(enquiry);

        props.setUseChecksum(useChecksumCheck.isSelected());
        props.setChecksumByteLength(checksumLen);
        props.setPositiveAckByte(ack);
        props.setNegativeAckByte(nak);
        props.setMaxRetransmissions(maxRetrans);
        props.setAckTimeoutMs(ackTimeout);
        props.setFrameTimeoutMs(frameTimeout);

        props.setAutoResultAcceptance(autoResultAcceptanceCheck.isSelected());
        props.setResultAcceptanceStatus(resultAccStatus);
        props.setAutoPollResponse(autoPollResponseCheck.isSelected());
        props.setAutoEnqAck(autoEnqAckCheck.isSelected());

        props.setOrderLookupEnabled(orderLookupCheck.isSelected());
        props.setOrderQueueKey(orderQueueKey);

        props.setIncludeChecksumInPayload(includeChecksumCheck.isSelected());
        props.setMessageOutputFormat(outputFormat);
        props.setServerMode(serverModeCheck.isSelected());
        props.setWireDebugEnabled(wireDebugCheck.isSelected());

        // Keep Mirth's base FrameModeProperties frame-byte fields in sync
        // so any Mirth-internal reader of startOfMessageBytes/endOfMessageBytes
        // sees the same values as the Dimension-specific fields.
        props.setStartOfMessageBytes(String.format("%02X", startOfFrame & 0xFF));
        props.setEndOfMessageBytes(String.format("%02X", endOfFrame & 0xFF));
        return true;
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
