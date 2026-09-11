package com.mirth.connect.donkey.util.purge;
import java.util.Map;
public interface Purgable {
    String getPluginPointName();
    Map<String, Object> getPurgedProperties();
}
