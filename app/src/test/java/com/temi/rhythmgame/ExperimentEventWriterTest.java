package com.temi.rhythmgame;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ExperimentEventWriterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void appendsEventsWithOneHeader() throws Exception {
        File file = new File(temp.getRoot(), "events.csv");
        ExperimentEventWriter.append(file, "one,T0,1,2\n");
        ExperimentEventWriter.append(file, "one,T1,3,4\n");
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertEquals("session_id,event,wall_time_epoch_ms,elapsed_realtime_ms\none,T0,1,2\none,T1,3,4\n", content);
    }

    @Test public void reportsWriteFailure() throws Exception {
        try {
            ExperimentEventWriter.append(temp.getRoot(), "one,T0,1,2\n");
            fail("writing to a directory should fail");
        } catch (IOException expected) { }
    }
}
