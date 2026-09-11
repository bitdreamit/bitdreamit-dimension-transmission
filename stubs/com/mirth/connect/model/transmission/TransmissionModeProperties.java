package com.mirth.connect.model.transmission;
import java.util.Map;
import com.mirth.connect.donkey.util.purge.Purgable;
public abstract class TransmissionModeProperties implements Purgable {
    public abstract String getPluginPointName();
}
