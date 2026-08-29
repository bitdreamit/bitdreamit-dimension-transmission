package com.mirth.connect.plugins;
public abstract class TransmissionModePlugin {
    public TransmissionModePlugin(String name) { }
    public abstract TransmissionModeClientProvider createProvider();
    public abstract String getPluginPointName();
}
