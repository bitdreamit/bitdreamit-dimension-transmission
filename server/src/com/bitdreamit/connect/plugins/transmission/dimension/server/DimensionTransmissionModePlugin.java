package com.bitdreamit.connect.plugins.transmission.dimension.server;

import java.io.InputStream;
import java.io.OutputStream;

import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionConstants;
import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionTransmissionModeProperties;
import com.mirth.connect.donkey.server.message.StreamHandler;
import com.mirth.connect.donkey.server.message.batch.BatchStreamReader;
import com.mirth.connect.model.transmission.TransmissionModeProperties;
import com.mirth.connect.plugins.TransmissionModeProvider;

/**
 * Server-side transmission-mode plugin for the Siemens Dimension native
 * host interface (EXL / RxL / Xpand).
 *
 * <p>Extends Mirth's TransmissionModeProvider (the same base class Mirth's
 * built-in MLLPModeProvider uses). Mirth's DefaultExtensionController
 * instantiates this class via the &lt;serverClasses&gt; entry in plugin.xml
 * and registers it with the TransmissionModeController.</p>
 *
 * <p>The mode appears in the "Transmission Mode" dropdown of the standard
 * TCP Listener / TCP Sender connectors AND of the BitDreamIT Serial
 * Reader / Serial Writer connectors (the serial connector resolves
 * transmission-mode providers through the same ExtensionController
 * registry and drives them through this getStreamHandler()).</p>
 */
public class DimensionTransmissionModePlugin extends TransmissionModeProvider {

    public DimensionTransmissionModePlugin() {
        super();
    }

    @Override
    public String getPluginPointName() {
        return DimensionConstants.PLUGIN_NAME;
    }

    // NOTE: no @Override - getPluginPointDescription() is NOT declared as abstract
    // in the real Mirth 4.5.2 TransmissionModeProvider base class, so @Override fails.
    public String getPluginPointDescription() {
        return "Siemens Dimension native host-interface transmission mode for Mirth Connect. "
             + "Implements <STX> TYPE <FS> data <FS> <CHK> <ETX> framing with Add-Mod-256 "
             + "checksum, per-frame ACK/NAK handshaking (4 retransmissions, 1-second timer), "
             + "and optional auto responses (Result Acceptance 'M', No Request 'N'). "
             + "Transport-agnostic: works with TCP Listener / TCP Sender and the "
             + "BitDreamIT Serial Reader / Serial Writer connectors.";
    }

    @Override
    public StreamHandler getStreamHandler(InputStream inputStream,
                                          OutputStream outputStream,
                                          BatchStreamReader batchStreamReader,
                                          TransmissionModeProperties properties) {
        DimensionTransmissionModeProperties dimensionProps;
        if (properties instanceof DimensionTransmissionModeProperties) {
            dimensionProps = (DimensionTransmissionModeProperties) properties;
        } else {
            dimensionProps = new DimensionTransmissionModeProperties();
        }
        return new DimensionStreamHandler(inputStream, outputStream,
                                          batchStreamReader, dimensionProps);
    }

    // NOTE: no @Override - getDefaultProperties() is NOT declared in the real Mirth 4.5.2
    // TransmissionModeProvider base class, so @Override fails.
    public TransmissionModeProperties getDefaultProperties() {
        return new DimensionTransmissionModeProperties();
    }
}
