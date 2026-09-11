package com.mirth.connect.donkey.server.message;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import com.mirth.connect.donkey.server.message.batch.BatchStreamReader;
public abstract class StreamHandler {
    protected InputStream inputStream;
    protected OutputStream outputStream;
    public StreamHandler(InputStream inputStream, OutputStream outputStream, BatchStreamReader batchStreamReader) {
        this.inputStream = inputStream; this.outputStream = outputStream;
    }
    public abstract byte[] read() throws IOException;
    public abstract void write(byte[] data) throws IOException;
    public abstract void commit(boolean success) throws IOException;
}
