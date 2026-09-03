package app;

import app.core.GameVersion;
import app.mappings.MappingFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automatic mappings discovery: format sniffing, version-aware ranking
 * and the empty case. Uses synthetic mapping samples only.
 */
class MappingFinderTest {

    @TempDir
    Path tmp;

    private String savedHome;

    @org.junit.jupiter.api.BeforeEach
    void isolateAppHome() throws Exception {
        // The finder always scans the shared download cache: point it at a
        // fresh temp dir so tests never see the user's real downloads.
        savedHome = System.getProperty("mcdecompiler.home");
        Path fakeHome = tmp.resolve("fake-home");
        Files.createDirectories(fakeHome);
        System.setProperty("mcdecompiler.home", fakeHome.toString());
    }

    @org.junit.jupiter.api.AfterEach
    void restoreAppHome() {
        if (savedHome == null) {
            System.clearProperty("mcdecompiler.home");
        } else {
            System.setProperty("mcdecompiler.home", savedHome);
        }
    }

    private static final String MOJANG = """
            net.minecraft.client.Minecraft -> abc:
                void tick() -> a
            """;

    private static final String TINY = """
            tiny\t2\t0\tofficial\tnamed
            c\tofficial/net/minecraft/Minecraft\tnamed/net/minecraft/Minecraft
            """;

    private Path writeLib() throws Exception {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("client-1.20.1.txt"), MOJANG, StandardCharsets.UTF_8);
        Files.writeString(lib.resolve("server-1.19.txt"), MOJANG, StandardCharsets.UTF_8);
        Files.writeString(lib.resolve("beta-tiny.tiny"), TINY, StandardCharsets.UTF_8);
        Files.writeString(lib.resolve("notes.txt"), "groceries: milk, eggs\n", StandardCharsets.UTF_8);
        return lib;
    }

    @Test
    void prefersVersionAndFormatMatch() {
        assertDoesNotThrow(() -> {
            Path lib = writeLib();
            List<MappingFinder.Candidate> found = MappingFinder.search(
                    GameVersion.classify("1.20.1"), null, null, lib);
            assertFalse(found.isEmpty(), "expected candidates");
            MappingFinder.Candidate best = found.get(0);
            assertTrue(best.path().toString().contains("client-1.20.1"),
                    "best should be the version-matching mojang file, got: " + best);
            assertEquals("mojang", best.format());
        });
    }

    @Test
    void tinyWinsForBetaEra() throws Exception {
        Path lib = writeLib();
        List<MappingFinder.Candidate> found = MappingFinder.search(
                GameVersion.classify("b1.7.3"), null, null, lib);
        assertFalse(found.isEmpty());
        assertTrue(found.get(0).path().toString().endsWith(".tiny"),
                "beta era should prefer tiny, got: " + found.get(0));
    }

    @Test
    void junkFilesAreIgnored() throws Exception {
        Path lib = writeLib();
        List<MappingFinder.Candidate> found = MappingFinder.search(
                GameVersion.classify("1.20.1"), null, null, lib);
        assertTrue(found.stream().noneMatch(c -> c.path().toString().endsWith("notes.txt")),
                "plain text must not be a candidate");
    }

    @Test
    void emptyLibraryGivesNoCandidates() throws Exception {
        Path empty = tmp.resolve("empty-lib");
        Files.createDirectories(empty);
        assertTrue(MappingFinder.search(GameVersion.classify("1.20.1"), null, null, empty).isEmpty());
        assertTrue(MappingFinder.bestFor(GameVersion.classify("1.20.1"), null, null, null).isEmpty(),
                "no sources at all must yield nothing");
    }

    @Test
    void explicitLibraryBeatsCacheOnTie() throws Exception {
        Path lib = tmp.resolve("tie-lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("client-1.20.1.txt"), MOJANG, StandardCharsets.UTF_8);
        Path cache = Path.of(System.getProperty("mcdecompiler.home"), "mappings", "pack");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("client-1.20.1.txt"), MOJANG, StandardCharsets.UTF_8);
        List<MappingFinder.Candidate> found = MappingFinder.search(
                GameVersion.classify("1.20.1"), null, null, lib);
        assertFalse(found.isEmpty());
        MappingFinder.Candidate best = found.get(0);
        assertEquals(MappingFinder.Origin.LIBRARY, best.origin(),
                "explicit library must win ties over the implicit cache, got: " + best);
        assertTrue(best.path().toString().contains("tie-lib"));
    }

    @Test
    void versionTokenBoundaries() throws Exception {
        Path lib = tmp.resolve("lib2");
        Files.createDirectories(lib);
        // 1.1 must NOT match 1.19.
        Files.writeString(lib.resolve("client-1.19.txt"), MOJANG, StandardCharsets.UTF_8);
        List<MappingFinder.Candidate> found = MappingFinder.search(
                GameVersion.classify("1.1"), null, null, lib);
        assertFalse(found.isEmpty());
        assertTrue(found.get(0).score() < MappingFinder.AUTO_FILL_THRESHOLD,
                "1.19 file must not auto-fill for 1.1 (score " + found.get(0).score() + ")");
    }
}
