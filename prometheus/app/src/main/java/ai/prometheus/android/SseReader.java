package ai.prometheus.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** SSE framing with incremental UTF-8 decoding; inference stays in llama.cpp. */
final class SseReader {
    interface Event { boolean accept(String data) throws Exception; }
    static void read(InputStream input, Event event) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        if (!event.accept(data.toString())) return;
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(line.length() > 5 && line.charAt(5) == ' ' ? 6 : 5));
                    if (data.length() > 2_000_000) throw new IOException("Oversized response event");
                }
            }
            throw new IOException("The local runtime disconnected before completing the response");
        }
    }
}
