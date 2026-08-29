package com.mirth.connect.plugins;
import java.io.InputStream;
import java.io.OutputStream;
import com.mirth.connect.donkey.server.message.StreamHandler;
import com.mirth.connect.donkey.server.message.batch.BatchStreamReader;
import com.mirth.connect.model.transmission.TransmissionModeProperties;
public abstract class TransmissionModeProvider {
    public abstract String getPluginPointName();
    public abstract StreamHandler getStreamHandler(InputStream inputStream, OutputStream outputStream, BatchStreamReader batchStreamReader, TransmissionModeProperties properties);
}
