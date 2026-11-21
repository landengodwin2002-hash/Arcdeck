package ai.prometheus.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SseReaderTest {
    @Test public void utf8SurvivesEveryByteBoundary() throws Exception {
        byte[] wire = (": keepalive\r\ndata: café 雪 🐈\r\n\r\ndata: [DONE]\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(wire)) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        List<String> events = new ArrayList<>();
        SseReader.read(fragmented, data -> {
            if (data.equals("[DONE]")) return false;
            events.add(data); return true;
        });
        assertEquals(Collections.singletonList("café 雪 🐈"), events);
    }
    @Test public void multilineDataAndCommentFraming() throws Exception {
        String wire = ":comment\ndata: first\ndata: second\n\nevent: complete\ndata: [DONE]\n\n";
        List<String> events = new ArrayList<>();
        SseReader.read(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), data -> {
            events.add(data); return !data.equals("[DONE]");
        });
        assertEquals(Arrays.asList("first\nsecond", "[DONE]"), events);
    }
    @Test public void interruptedSocketIsNotACompletedAnswer() throws Exception {
        try {
            SseReader.read(new ByteArrayInputStream("data: partial\n\n".getBytes(StandardCharsets.UTF_8)), data -> true);
            fail("An unfinished stream must surface an error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("disconnected"));
        }
    }
}
