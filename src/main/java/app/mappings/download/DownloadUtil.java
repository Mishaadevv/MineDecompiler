package app.mappings.download;

import app.core.DecompileProgressListener;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Minimal HTTPS downloader (JDK built-in, no extra deps): redirect-following,
 * bounded timeouts, atomic writes, optional SHA-1/size verification against
 * publisher metadata. Cache-aware: matching local files are reused.
 */
public final class DownloadUtil {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private DownloadUtil() {
    }

    public static HttpClient client() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** GETs a small text document (manifests, checksums). */
    public static String getText(String url, DecompileProgressListener listener) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "mcdecompiler/1.0 (+local decompiler tool)")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IOException("HTTP " + res.statusCode() + " for " + url);
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    /**
     * Downloads {@code url} to {@code dest} (parent dirs created). When the
     * destination already exists and matches {@code expectedSha1} (or no hash
     * is known and the file is non-empty), it is reused without network use.
     */
    public static Path download(String url, Path dest, String expectedSha1, long expectedSize,
                                DecompileProgressListener listener, String what) throws IOException {
        if (Files.isRegularFile(dest) && Files.size(dest) > 0) {
            if (expectedSha1 == null || expectedSha1.isEmpty()
                    || expectedSha1.equalsIgnoreCase(sha1(dest))) {
                listener.onStatus("Reusing cached " + what + ": " + dest);
                return dest;
            }
            listener.onWarning("Cached file mismatch, re-downloading: " + dest);
        }
        listener.onStatus("Downloading " + what + " ...");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "mcdecompiler/1.0 (+local decompiler tool)")
                    .timeout(Duration.ofMinutes(3))
                    .GET()
                    .build();
            Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
            Files.createDirectories(dest.getParent());
            HttpResponse<Path> res = client().send(req,
                    HttpResponse.BodyHandlers.ofFile(tmp));
            if (res.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IOException("HTTP " + res.statusCode() + " for " + url);
            }
            if (expectedSize > 0 && Files.size(tmp) != expectedSize) {
                Files.deleteIfExists(tmp);
                throw new IOException("Size mismatch for " + url
                        + " (expected " + expectedSize + ", got " + Files.size(tmp) + ")");
            }
            if (expectedSha1 != null && !expectedSha1.isEmpty()
                    && !expectedSha1.equalsIgnoreCase(sha1(tmp))) {
                Files.deleteIfExists(tmp);
                throw new IOException("SHA-1 mismatch for " + url + " (transfer corrupted?)");
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            listener.onStatus("Saved " + what + " (" + Files.size(dest) / 1024 + " KB).");
            return dest;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    public static String sha1(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("SHA-1 failed for " + file, e);
        }
    }

    /** True when {@code file} exists and is younger than {@code maxAgeMillis}. */
    public static boolean isFresh(Path file, long maxAgeMillis) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0
                    && (System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis()
                    < maxAgeMillis);
        } catch (IOException e) {
            return false;
        }
    }
}
