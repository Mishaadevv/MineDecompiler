package app.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Mapping file reading helpers: transparent {@code .gz} handling
 * (community packs like Feather ship {@code *-tiny.gz}) and cheap
 * head-peeks for format sniffing.
 */
public final class MappingFiles {

    private MappingFiles() {
    }

    public static boolean isGzip(Path file) {
        String n = file.getFileName().toString().toLowerCase();
        if (n.endsWith(".gz")) {
            return true;
        }
        // Magic bytes 1F 8B, for extension-less downloads.
        try (InputStream in = Files.newInputStream(file)) {
            return in.read() == 0x1F && in.read() == 0x8B;
        } catch (IOException e) {
            return false;
        }
    }

    public static BufferedReader openReader(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        if (isGzip(file)) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Reads up to {@code maxLines} for sniffing, gzip-transparent. */
    public static String peekHead(Path file, int maxLines) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = openReader(file)) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n++ < maxLines) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }
}
