package com.temi.rhythmgame;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ExperimentEventWriter {
    private ExperimentEventWriter() { }

    static void append(File file, String row) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            if (file.length() == 0) {
                out.write("session_id,event,wall_time_epoch_ms,elapsed_realtime_ms\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(row.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
}
