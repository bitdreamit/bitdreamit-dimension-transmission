package com.mirth.connect.model.transmission.framemode;
import com.mirth.connect.model.transmission.TransmissionModeProperties;
public abstract class FrameModeProperties extends TransmissionModeProperties {
    private String pluginPointName;
    private String startOfMessageBytes;
    private String endOfMessageBytes;
    public FrameModeProperties(String pluginPointName) { this.pluginPointName = pluginPointName; }
    public String getPluginPointName() { return pluginPointName; }
    public String getStartOfMessageBytes() { return startOfMessageBytes; }
    public void setStartOfMessageBytes(String b) { this.startOfMessageBytes = b; }
    public String getEndOfMessageBytes() { return endOfMessageBytes; }
    public void setEndOfMessageBytes(String b) { this.endOfMessageBytes = b; }
}
