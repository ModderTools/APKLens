package com.apklens.app.engine;

import java.io.IOException;
import java.io.OutputStream;

/** Abstraction over "where the final ZIP goes" (direct file or MediaStore). */
public interface ZipSink {
    OutputStream open() throws IOException;
    void commit() throws IOException;
    void abort();
    String describe();
    String mode();        // "direct" | "media"
    String uriString();
    String filePath();
    String projectId();
}
