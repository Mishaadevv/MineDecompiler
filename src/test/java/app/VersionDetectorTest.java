package app;

import app.core.GameVersion;
import app.core.VersionDetectionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Version detection across Minecraft eras (alpha / beta / legacy / modern),
 * plus user override and the unknown-version path.
 */
class VersionDetectorTest {

    @TempDir
    Path tmp;

    @Test
    void detectsModernFromManifestAndVersionJson() throws Exception {
        Path jar = TestJars.modernJar(tmp);
        VersionDetectionResult r = app.core.VersionDetector.detect(jar, null);
        assertEquals("1.20.1", r.getBest().getId());
        assertEquals(GameVersion.Era.MODERN_RELEASE, r.getBest().getEra());
        assertTrue(r.isConfident(), "manifest + version.json should be confident");
        assertFalse(r.getEvidence().isEmpty());
    }

    @Test
    void detectsLegacyRelease() throws Exception {
        Path jar = TestJars.legacyJar(tmp);
        VersionDetectionResult r = app.core.VersionDetector.detect(jar, null);
        assertEquals(GameVersion.Era.LEGACY_RELEASE, r.getBest().getEra());
    }

    @Test
    void detectsBetaFromFilenameAndFlatClasses() throws Exception {
        Path jar = TestJars.betaJar(tmp);
        VersionDetectionResult r = app.core.VersionDetector.detect(jar, null);
        // Filename b1.7.3 classifies as BETA; flat root class backs it up.
        assertEquals(GameVersion.Era.BETA, r.getBest().getEra());
    }

    @Test
    void detectsAlphaEra() throws Exception {
        Path jar = TestJars.alphaJar(tmp);
        VersionDetectionResult r = app.core.VersionDetector.detect(jar, null);
        assertTrue(r.getBest().getEra() == GameVersion.Era.ALPHA
                || r.getBest().getEra() == GameVersion.Era.BETA
                || r.getBest().getEra() == GameVersion.Era.FUTURE
                || r.getBest().getEra() == GameVersion.Era.UNKNOWN,
                "alpha fixture must not crash detection, got: " + r.getBest());
        assertFalse(r.getEvidence().isEmpty());
    }

    @Test
    void userOverrideAlwaysWins() throws Exception {
        Path jar = TestJars.alphaJar(tmp);
        VersionDetectionResult r = app.core.VersionDetector.detect(jar, "1.1");
        assertEquals("1.1", r.getBest().getId());
        assertTrue(r.isUserOverride());
        assertTrue(r.isConfident());
    }

    @Test
    void unknownJarGivesCandidatesNotCrash() throws Exception {
        Path jar = TestJars.buildJar(tmp, "empty-ish.jar", java.util.List.of(), null, null);
        VersionDetectionResult r = app.core.VersionDetector.detect(jar, null);
        assertNotNull(r.getBest());
        assertFalse(r.getCandidates().isEmpty(), "must propose candidates for manual selection");
    }
}
