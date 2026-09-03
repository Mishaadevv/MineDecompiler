package app.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Small hashing / IO helpers. */
public final class HashUtils {

    private HashUtils() {
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
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
            throw new IOException("SHA-256 failed for " + file, e);
        }
    }

    public static String shortHash(String sha256) {
        if (sha256 == null) {
            return "unknown";
        }
        return sha256.length() > 16 ? sha256.substring(0, 16) : sha256;
    }
}
