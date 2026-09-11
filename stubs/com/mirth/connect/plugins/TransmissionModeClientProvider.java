package com.mirth.connect.plugins;
import javax.swing.JComponent;
import com.mirth.connect.model.transmission.TransmissionModeProperties;
public abstract class TransmissionModeClientProvider {
    public abstract String getSampleLabel();
    public abstract String getSampleValue();
    public abstract TransmissionModeProperties getProperties();
    public abstract TransmissionModeProperties getDefaultProperties();
    public abstract void setProperties(TransmissionModeProperties properties);
    public abstract boolean checkProperties(TransmissionModeProperties properties, boolean highlight);
    public abstract void resetInvalidProperties();
    public abstract JComponent getSettingsComponent();
}
