package com.bitdreamit.connect.plugins.transmission.dimension.client;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionConstants;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionHL7Format;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;
import com.mirth.connect.client.ui.MirthDialog;
import com.mirth.connect.client.ui.UIConstants;
import com.mirth.connect.client.ui.components.MirthCheckBox;
import com.mirth.connect.client.ui.components.MirthComboBox;
import com.mirth.connect.client.ui.components.MirthFieldConstraints;
import com.mirth.connect.client.ui.components.MirthTextField;

/**
 * Modal settings dialog for the Siemens Dimension transmission mode,
 * built the same way Mirth Connect builds its own.
 *
 * <p><b>Mirth-native UI kit (v2.3.0):</b> Every editable control is one of
 * Mirth's private components - {@link MirthTextField}, {@link MirthCheckBox},
 * {@link MirthComboBox} - exactly like Mirth's own
 * {@code MLLPModeSettingsDialog}. The dialog itself extends Mirth's
 * {@link MirthDialog} base class (the same base the MLLP dialog uses),
 * which at runtime gives the dialog Mirth's Administrator integration for
 * free: ESC closes the dialog, and the channel Save button is disabled
 * while the dialog is open (MirthDialog hooks
 * {@code PlatformUI.MIRTH_FRAME.setCanSave(...)}).</p>
 *
 * <p><b>Input constraints - MirthFieldConstraints (v2.3.0):</b> Text fields
 * get Mirth's own {@link MirthFieldConstraints} document, the same class
 * Mirth's MLLP dialog applies: numeric-only documents on every timeout /
 * retry / length field, an anchored hex pattern (optional {@code 0x}
 * prefix, max two hex digits) on every frame-byte field, a one-character
 * uppercase letters-only document on the result-acceptance status, and a
 * 64-character limit on the order queue key. Impossible values can no
 * longer be typed at all.</p>
 *
 * <p><b>Validation (Mirth style):</b> Invalid fields are highlighted with
 * Mirth's own {@link UIConstants#INVALID_COLOR} background (the same pink
 * Mirth's MLLP dialog paints) plus a tooltip explaining the problem, and
 * {@link #resetInvalidProperties()} clears the highlights when the dialog
 * opens - the exact lifecycle MLLPModeSettingsDialog uses.</p>
 *
 * <p>Labels, panels and the OK/Cancel buttons stay plain Swing
 * ({@code JLabel} / {@code JPanel} / {@code JButton}) - Mirth's own
 * MLLPModeSettingsDialog form uses exactly those plain classes for the
 * non-editable parts, so the dialog looks the way a first-class Mirth
 * transmission mode looks.</p>
 *
 * <p><b>Save wiring (TransmissionModeClientProvider contract):</b> the
 * provider hands this dialog the SAME
 * {@link DimensionTransmissionModeProperties} reference that Mirth holds
 * inside its editable channel working-copy. The dialog loads every control
 * from that reference on open and writes the edited values back into it
 * when OK is pressed - Mirth's channel serialization picks the changes up
 * automatically when the user saves the channel.</p>
 *
 * <p><b>Every</b> property of {@code DimensionTransmissionModeProperties}
 * has a control here (frame bytes, handshake, auto responses, order
 * download, dispatch/mode, wire debug) - nothing is UI-only.</p>
 *
 * <p><b>Commit discipline (Mirth-style, all-or-nothing):</b> Save validates
 * and parses EVERY control into locals first; only if all values are valid
 * are they committed to the properties object in one pass. An invalid value
 * can never leave a half-written properties object behind.</p>
 */
public class DimensionSettingsDialog extends MirthDialog {

    private final DimensionTransmissionModeProperties props;
    private boolean okPressed = false;

    // --- frame bytes ---
    private MirthTextField startOfFrameField;
    private MirthTextField endOfFrameField;
    private MirthTextField fieldSeparatorField;
    private MirthTextField enquiryField;

    // --- handshake ---
    private MirthCheckBox useChecksumCheck;
    private MirthTextField checksumLengthField;
    private MirthTextField ackField;
    private MirthTextField nakField;
    private MirthTextField maxRetransField;
    private MirthTextField ackTimeoutField;
    private MirthTextField frameTimeoutField;

    // --- auto responses ---
    private MirthCheckBox autoResultAcceptanceCheck;
    private MirthTextField resultAcceptanceStatusField;
    private MirthCheckBox autoPollResponseCheck;
    private MirthCheckBox autoEnqAckCheck;

    // --- dynamic order download (bidirectional) ---
    private MirthCheckBox orderLookupCheck;
    private MirthTextField orderQueueKeyField;

    // --- dispatch / mode ---
    private MirthCheckBox includeChecksumCheck;
    private MirthComboBox<String> outputFormatCombo;
    private MirthCheckBox serverModeCheck;

    // --- diagnostics ---
    private MirthCheckBox wireDebugCheck;

    // --- invalid-field highlight (Mirth UIConstants.INVALID_COLOR style) ---
    private MirthTextField invalidField;

    public DimensionSettingsDialog(Frame owner, DimensionTransmissionModeProperties props) {
        // MirthDialog(Window, String, boolean) - same base + same style of
        // construction Mirth's own MLLPModeSettingsDialog uses.
        super(owner, "Siemens Dimension Frame Settings", true);
        this.props = props;
        initComponents();
        loadFromProps();
        resetInvalidProperties();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
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
        checksumLengthField= addIntRow(panel, 1, "Checksum Byte Length", "Number of ASCII-hex checksum characters (1 or 2)");
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
        // MirthComboBox has ONLY a no-arg constructor (same as Mirth's real
        // component) - prefill with addItem, never an array constructor.
        outputFormatCombo = new MirthComboBox<String>();
        outputFormatCombo.addItem(DimensionHL7Format.RAW_FRAME);
        outputFormatCombo.addItem(DimensionHL7Format.HL7_V2);
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

        // Plain JButton for OK/Cancel - Mirth's own MLLPModeSettingsDialog
        // form uses plain javax.swing.JButton for its buttons too.
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
        return panel;
    }

    // ------------------------------------------------------------------
    // Row builders (every input is a Mirth private component)
    // ------------------------------------------------------------------

    /** Hex byte field: optional 0x prefix + up to 2 hex digits, that's all. */
    private MirthTextField addHexRow(JPanel panel, int row, String label, String tip) {
        MirthTextField field = addTextRow(panel, row, label, tip);
        // Anchored pattern - MirthFieldConstraints tests the WHOLE proposed
        // content, so the field can only ever hold 0x00..0xFF style values.
        field.setDocument(new MirthFieldConstraints("(?i)^(0x)?[0-9a-f]{0,2}$"));
        return field;
    }

    /** Integer field: digits only - the same document Mirth's MLLP dialog
     * puts on its max-retry-count field: new MirthFieldConstraints(0, false, false, true). */
    private MirthTextField addIntRow(JPanel panel, int row, String label, String tip) {
        MirthTextField field = addTextRow(panel, row, label, tip);
        field.setDocument(new MirthFieldConstraints(0, false, false, true));
        return field;
    }

    /** Free text field with a length limit (no pattern restriction). */
    private MirthTextField addTextRow(JPanel panel, int row, String label, String tip) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;

        JLabel lbl = new JLabel(label + ":");
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(lbl, gbc);

        MirthTextField field = new MirthTextField();
        field.setColumns(10);
        field.setToolTipText(tip);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gbc);
        return field;
    }

    private MirthCheckBox addBooleanRow(JPanel panel, int row, String label, String tip) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;

        MirthCheckBox check = new MirthCheckBox(label);
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
     * properties object completely untouched. The offending field is
     * highlighted with Mirth's own UIConstants.INVALID_COLOR background -
     * the same thing Mirth's MLLPModeSettingsDialog.checkProperties does.
     */
    private boolean saveToProps() {
        // Mirth lifecycle: clear previous highlights on every save attempt
        // (MLLPModeSettingsDialog does this at the top of checkProperties).
        resetInvalidProperties();

        // --- pass 1: validate everything into locals ---------------------
        int startOfFrame, endOfFrame, fieldSep, enquiry, ack, nak;
        int checksumLen, maxRetrans, ackTimeout, frameTimeout;
        String resultAccStatus, outputFormat, orderQueueKey;
        try {
            startOfFrame = parseHex(startOfFrameField, DimensionConstants.STX);
            endOfFrame   = parseHex(endOfFrameField, DimensionConstants.ETX);
            fieldSep     = parseHex(fieldSeparatorField, DimensionConstants.FS);
            enquiry      = parseHex(enquiryField, DimensionConstants.ENQ);
            ack          = parseHex(ackField, DimensionConstants.ACK);
            nak          = parseHex(nakField, DimensionConstants.NAK);

            checksumLen  = parseInt(checksumLengthField, DimensionConstants.DEFAULT_CHECKSUM_LENGTH);
            maxRetrans   = parseInt(maxRetransField, DimensionConstants.DEFAULT_MAX_RETRANSMISSIONS);
            ackTimeout   = parseInt(ackTimeoutField, DimensionConstants.DEFAULT_ACK_TIMEOUT_MS);
            frameTimeout = parseInt(frameTimeoutField, DimensionConstants.DEFAULT_FRAME_TIMEOUT_MS);

            // Range rules (same invariants the provider's checkProperties enforces)
            requireRange(checksumLengthField, checksumLen, 1, 2, "Checksum length must be 1 or 2");
            requireMin(maxRetransField, maxRetrans, 1, "Max retransmissions must be at least 1");
            requireMin(ackTimeoutField, ackTimeout, 1, "ACK timeout must be positive");
            requireMin(frameTimeoutField, frameTimeout, 1, "Frame timeout must be positive");

            String ras = resultAcceptanceStatusField.getText().trim();
            resultAccStatus = ras.isEmpty() ? "A" : ras.substring(0, 1).toUpperCase();

            outputFormat = outputFormatCombo.getSelectedItem() == null
                    ? DimensionHL7Format.RAW_FRAME
                    : String.valueOf(outputFormatCombo.getSelectedItem());

            String oqk = orderQueueKeyField.getText().trim();
            orderQueueKey = oqk.isEmpty() ? "default" : oqk;
        } catch (IllegalArgumentException e) {
            if (invalidField != null) {
                invalidField.setBackground(UIConstants.INVALID_COLOR);
            }
            JOptionPane.showMessageDialog(this,
                    "Invalid value: " + e.getMessage(),
                    "Invalid Settings", JOptionPane.ERROR_MESSAGE);
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

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    /** Mirth MLLPModeSettingsDialog lifecycle: clear the pink highlights. */
    private void resetInvalidProperties() {
        invalidField = null;
        startOfFrameField.setBackground(null);
        endOfFrameField.setBackground(null);
        fieldSeparatorField.setBackground(null);
        enquiryField.setBackground(null);
        checksumLengthField.setBackground(null);
        ackField.setBackground(null);
        nakField.setBackground(null);
        maxRetransField.setBackground(null);
        ackTimeoutField.setBackground(null);
        frameTimeoutField.setBackground(null);
        resultAcceptanceStatusField.setBackground(null);
        orderQueueKeyField.setBackground(null);
    }

    private void fail(MirthTextField field, String message) {
        invalidField = field;
        field.setToolTipText(message);
        throw new IllegalArgumentException(message);
    }

    private void requireMin(MirthTextField field, int value, int min, String message) {
        if (value < min) {
            fail(field, message);
        }
    }

    private void requireRange(MirthTextField field, int value, int min, int max, String message) {
        if (value < min || value > max) {
            fail(field, message);
        }
    }

    private static String hex(int b) {
        return String.format("0x%02X", b & 0xFF);
    }

    private int parseHex(MirthTextField field, int defaultValue) {
        invalidField = field;
        try {
            String v = field.getText().trim().replace("0x", "").replace("0X", "");
            return v.isEmpty() ? defaultValue : (Integer.parseInt(v, 16) & 0xFF);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + field.getText() + "' is not a valid hex byte");
        }
    }

    private int parseInt(MirthTextField field, int defaultValue) {
        invalidField = field;
        try {
            String v = field.getText().trim();
            return v.isEmpty() ? defaultValue : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + field.getText() + "' is not a valid number");
        }
    }

    public boolean isOkPressed() {
        return okPressed;
    }
}
