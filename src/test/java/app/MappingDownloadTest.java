package app;

import app.core.GameVersion;
import app.mappings.MappingSet;
import app.mappings.TinyMappingProvider;
import app.mappings.download.FeatherMappingRepository;
import app.mappings.download.MojangMappingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline-safe tests for auto-download support: real Feather Tiny-v1 shape,
 * gzip packs, version-id normalization and manifest/metadata matching.
 * (No live network in unit tests; the live path is verified manually.)
 */
class MappingDownloadTest {

    @TempDir
    Path tmp;

    // Shape verified against real Feather packs (OrnitheMC, CC0).
    private static final String FEATHER_V1 = "v1\tofficial\tintermediary\tnamed\n"
            + "CLASS\tgh\tnet/minecraft/unmapped/C_32171470\tnet/minecraft/client/Minecraft__32171470\n"
            + "CLASS\tnet/minecraft/client/Minecraft\tnet/minecraft/client/Minecraft\tnet/minecraft/client/Minecraft\n"
            + "FIELD\tgh\tI\tc\tf_31800787\twidth\n"
            + "METHOD\tgh\t()V\ta\tm_1234\ttick\n";

    @Test
    void parsesFeatherTinyV1() throws Exception {
        Path f = tmp.resolve("feather.tiny");
        Files.writeString(f, FEATHER_V1, StandardCharsets.UTF_8);
        MappingSet m = new TinyMappingProvider(f).loadMappings(GameVersion.classify("a1.2.3_01"));
        assertEquals("net/minecraft/client/Minecraft__32171470", m.mapClassName("gh"));
        assertEquals("tick", m.mapMethodName("gh", "a", "()V"));
        assertEquals("width", m.mapFieldName("gh", "c", "I"));
        assertEquals("net/minecraft/client/Minecraft", m.mapClassName("net/minecraft/client/Minecraft"));
    }

    @Test
    void loadsGzippedTiny() throws Exception {
        Path f = tmp.resolve("feather-tiny.gz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(f))) {
            out.write(FEATHER_V1.getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(TinyMappingProvider.looksLikeTiny(f), "gzipped tiny must be sniffed");
        MappingSet m = new app.mappings.FileMappingProvider(f)
                .loadMappings(GameVersion.classify("a1.2.3_01"));
        assertEquals("net/minecraft/client/Minecraft__32171470", m.mapClassName("gh"));
    }

    @Test
    void featherCompactIds() throws Exception {
        Method compact = FeatherMappingRepository.class
                .getDeclaredMethod("compactId", String.class);
        compact.setAccessible(true);
        assertEquals("a1.2.3_01", compact.invoke(null, "alpha 1.2.3_01"));
        assertEquals("b1.7.3", compact.invoke(null, "Beta 1.7.3"));
        assertEquals("1.20.1", compact.invoke(null, "1.20.1"));
        assertNull(compact.invoke(null, "unknown-modern"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void featherPicksExactBuild() throws Exception {
        Path meta = tmp.resolve("maven-metadata.xml");
        Files.writeString(meta, "<metadata><versioning><versions>"
                + "<version>a1.2.2+build.1</version>"
                + "<version>a1.2.3_01+build.1</version>"
                + "<version>a1.2.3_02+build.1</version>"
                + "</versions></versioning></metadata>", StandardCharsets.UTF_8);
        Method pick = FeatherMappingRepository.class
                .getDeclaredMethod("pickMappingVersion", Path.class, String.class);
        pick.setAccessible(true);
        assertEquals("a1.2.3_01+build.1", pick.invoke(null, meta, "a1.2.3_01"));
        assertNull(pick.invoke(null, meta, "9.9.9"));
    }

    @Test
    void mojangManifestMatching() throws Exception {
        Path manifest = tmp.resolve("manifest.json");
        Files.writeString(manifest, "{\"versions\": ["
                + "{\"id\": \"1.20.1\", \"url\": \"https://example.com/1.20.1.json\"},"
                + "{\"id\": \"b1.7.3\", \"url\": \"https://example.com/b1.7.3.json\"}"
                + "]}", StandardCharsets.UTF_8);
        Method find = MojangMappingRepository.class
                .getDeclaredMethod("findPackageUrl", Path.class, app.core.GameVersion.class);
        find.setAccessible(true);
        assertEquals("https://example.com/1.20.1.json",
                find.invoke(null, manifest, GameVersion.classify("1.20.1")));
        assertEquals("https://example.com/b1.7.3.json",
                find.invoke(null, manifest, GameVersion.classify("beta 1.7.3")));
        assertNull(find.invoke(null, manifest, GameVersion.classify("alpha 1.2.3_01")));
    }

    @Test
    void keywordClassNamesNeverRewriteKeywords() {
        app.mappings.MappingSet m = new app.mappings.MappingSet();
        m.mapClass("if", "net/minecraft/block/WheatBlock");
        m.mapClass("gh", "net/minecraft/client/Minecraft__32171470");
        m.mapMethod("gh", "a", "()V", "tick");
        String src = "public class gh {\n"
                + "    public void a() {\n"
                + "        if (true) {\n"
                + "            a();\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        var out = app.reconstruction.NameRemapper.remap(Map.of("gh", src), m);
        String remapped = out.sourcesByNewInternal.get("net/minecraft/client/Minecraft__32171470");
        assertNotNull(remapped);
        assertTrue(remapped.contains("if (true)"),
                "the 'if' keyword must survive, got:\n" + remapped);
        assertTrue(remapped.contains("tick()"),
                "real member rename must still work, got:\n" + remapped);
        assertFalse(remapped.contains("WheatBlock (true)"),
                "keyword must not become a class name, got:\n" + remapped);
    }

    @Test
    void downloaderWithoutNetworkReturnsNullSafely() {
        var dl = new app.mappings.download.MappingDownloader(List.of());
        assertNull(dl.fetchBest(GameVersion.classify("1.20.1"),
                tmp, app.core.DecompileProgressListener.silent()));
    }
}
