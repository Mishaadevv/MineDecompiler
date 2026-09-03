package app;

import app.core.GameVersion;
import app.mappings.FileMappingProvider;
import app.mappings.MappingSet;
import app.mappings.MojangMappingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping providers: Mojang ProGuard, Tiny, SRG and auto-detection,
 * plus the "no mappings" path that must never break the pipeline.
 */
class MappingProviderTest {

    @TempDir
    Path tmp;

    private static final String MOJANG_SAMPLE = """
            # Mojang sample
            net.minecraft.client.Minecraft -> abc:
                void tick() -> a
                int getFps() -> b
                int fps -> c
            """;

    private static final String SRG_SAMPLE = """
            CL: abc net/minecraft/client/Minecraft
            FD: abc/c net/minecraft/client/Minecraft/fps
            MD: abc/a ()V net/minecraft/client/Minecraft/tick ()V
            """;

    @Test
    void parsesMojangProGuard() throws Exception {
        Path f = tmp.resolve("client.txt");
        Files.writeString(f, MOJANG_SAMPLE, StandardCharsets.UTF_8);
        MappingSet m = new MojangMappingProvider(f)
                .loadMappings(GameVersion.classify("1.20.1"));
        assertEquals("net/minecraft/client/Minecraft", m.mapClassName("abc"));
        assertFalse(m.isEmpty());
    }

    @Test
    void parsesSrg() throws Exception {
        Path f = tmp.resolve("mappings.srg");
        Files.writeString(f, SRG_SAMPLE, StandardCharsets.UTF_8);
        MappingSet m = new app.mappings.SrgMappingProvider(f)
                .loadMappings(GameVersion.classify("1.6.4"));
        assertEquals("net/minecraft/client/Minecraft", m.mapClassName("abc"));
    }

    @Test
    void autoDetectsFormatFromFile() throws Exception {
        Path f = tmp.resolve("client.txt");
        Files.writeString(f, MOJANG_SAMPLE, StandardCharsets.UTF_8);
        MappingSet m = new FileMappingProvider(f)
                .loadMappings(GameVersion.classify("1.20.1"));
        assertEquals("net/minecraft/client/Minecraft", m.mapClassName("abc"));
    }

    @Test
    void missingFileGivesEmptySetNotException() throws Exception {
        MappingSet m = new MojangMappingProvider(tmp.resolve("does-not-exist.txt"))
                .loadMappings(GameVersion.classify("1.20.1"));
        assertNotNull(m);
        assertTrue(m.isEmpty());
        assertEquals("abc", m.mapClassName("abc"), "unmapped names pass through");
    }

    @Test
    void emptyProviderPassesThrough() throws Exception {
        MappingSet m = new app.mappings.EmptyMappingProvider()
                .loadMappings(GameVersion.classify("b1.7.3"));
        assertTrue(m.isEmpty());
    }
}
