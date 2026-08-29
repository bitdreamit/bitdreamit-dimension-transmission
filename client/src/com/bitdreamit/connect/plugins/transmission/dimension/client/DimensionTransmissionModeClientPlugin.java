package com.bitdreamit.connect.plugins.transmission.dimension.client;

import java.util.Collections;

import com.bitdreamit.connect.plugins.transmission.dimension.shared.DimensionConstants;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.TransmissionModeClientProvider;
import com.mirth.connect.plugins.TransmissionModePlugin;

/**
 * Client-side transmission-mode plugin for the Siemens Dimension native
 * host interface.
 *
 * <p>Extends Mirth's TransmissionModePlugin (the same base class Mirth's
 * built-in MLLPModePlugin uses). Mirth's client-side extension loader
 * (com.mirth.connect.client.ui.LoadedExtensions#initialize, called from
 * Frame#setupFrame) instantiates this class via the &lt;clientClasses&gt;
 * entry in plugin.xml.</p>
 *
 * <h2>XStream security registration (fixes ForbiddenClassException)</h2>
 *
 * <p>Mirth Connect (server AND Administrator) runs XStream with the
 * security framework enabled. The allow-list only covers
 * {@code com.mirth.connect.**}-style package prefixes. Classes from
 * third-party packages that end up embedded in serialized channel XML -
 * such as {@code DimensionTransmissionModeProperties} - are rejected on
 * DESERIALIZATION with {@code ForbiddenClassException} unless the plugin
 * registers the permission itself. Serialization is NOT security-checked,
 * but every deserialization path in the Administrator goes through
 * {@code ObjectXMLSerializer} and needs the class allow-listed.</p>
 *
 * <p><b>Important:</b> use the 3-argument
 * {@link ObjectXMLSerializer#allowTypes(List, List, List)} method - it
 * updates BOTH XStream instances (the primary one and the private
 * "references-mode" instance used for internal deserialization).</p>
 */
public class DimensionTransmissionModeClientPlugin extends TransmissionModePlugin {

    public DimensionTransmissionModeClientPlugin(String name) {
        super(name);

        // Allow-list this extension's package for the Administrator's XStream
        // security framework (entries containing '*' or '?' are treated as
        // wildcards by XStream's allowTypesByWildcard).
        ObjectXMLSerializer.getInstance().allowTypes(
                null,
                Collections.singletonList("com.bitdreamit.connect.plugins.transmission.dimension.**"),
                null);
    }

    @Override
    public TransmissionModeClientProvider createProvider() {
        return new DimensionClientProvider();
    }

    @Override
    public String getPluginPointName() {
        return DimensionConstants.PLUGIN_NAME;
    }

    // NOTE: no @Override - getPluginPointDescription() is NOT declared as abstract
    // in the real Mirth 4.5.2 TransmissionModePlugin base class, so @Override fails.
    public String getPluginPointDescription() {
        return "Siemens Dimension native host-interface transmission mode (client side). "
                + "Provides the settings panel for configuring STX/ETX/FS bytes, Add-Mod-256 "
                + "checksum, ACK/NAK handshaking, retransmission limits, and the optional "
                + "application-level auto responses (Result Acceptance 'M', No Request 'N').";
    }
}
