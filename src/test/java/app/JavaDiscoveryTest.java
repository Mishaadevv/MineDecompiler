package app;

import app.util.JavaAutoLauncher;
import app.util.JavaDiscovery;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automatic JVM selection: version-string parsing, ranking of discovered
 * runtimes and relaunch-command construction.
 */
class JavaDiscoveryTest {

    @Test
    void parsesMajorVersions() {
        assertEquals(8, JavaDiscovery.parseMajor("java version \"1.8.0_202\""));
        assertEquals(8, JavaDiscovery.parseMajor("openjdk version \"1.8.0_472\""));
        assertEquals(11, JavaDiscovery.parseMajor("openjdk version \"11.0.11\" 2021-04-20"));
        assertEquals(17, JavaDiscovery.parseMajor("openjdk version \"17.0.17\" 2025-10-21"));
        assertEquals(21, JavaDiscovery.parseMajor("openjdk version \"21.0.11\" 2026-07-21"));
        assertEquals(-1, JavaDiscovery.parseMajor("not a version at all!!!"));
        assertEquals(-1, JavaDiscovery.parseMajor(null));
    }

    @Test
    void discoversAtLeastCurrentRuntime() {
        List<JavaDiscovery.JvmInfo> all = JavaDiscovery.discover();
        assertFalse(all.isEmpty(), "must find at least the JVM running the tests");
        Path home = Path.of(System.getProperty("java.home")).toAbsolutePath();
        assertTrue(all.stream().anyMatch(j ->
                        j.home().equals(home) || j.javaExe().equals(JavaDiscovery.currentJavaExe())),
                "current runtime missing from discovery: " + all);
    }

    @Test
    void bestForIsLowestSufficient() {
        Optional<JavaDiscovery.JvmInfo> best = JavaDiscovery.bestFor(8);
        assertTrue(best.isPresent());
        assertTrue(best.get().major() >= 8);
        Optional<JavaDiscovery.JvmInfo> modern = JavaDiscovery.bestFor(JavaDiscovery.REQUIRED_MAJOR);
        // Tests themselves run on 17+, so this must hold here.
        assertTrue(modern.isPresent(), "test runtime must satisfy REQUIRED_MAJOR");
    }

    @Test
    void bestForImpossibleVersionIsEmpty() {
        assertTrue(JavaDiscovery.bestFor(999).isEmpty());
    }

    @Test
    void relaunchCommandPrefersJarMode() throws Exception {
        Path tmp = java.nio.file.Files.createTempFile("mcdec", ".jar");
        try {
            List<String> cmd = JavaAutoLauncher.buildCommand(
                    "C:\\Java\\bin\\java.exe", "app.cli.MainCli",
                    new String[]{tmp.toString(), "--output", "out"},
                    tmp + " --output out");
            assertTrue(cmd.contains("-jar"), "expected -jar relaunch, got: " + cmd);
            assertTrue(cmd.contains(tmp.toString()));
            assertFalse(cmd.contains("app.cli.MainCli"),
                    "main class must not be passed in -jar mode: " + cmd);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test
    void relaunchCommandFallsBackToClasspath() {
        List<String> cmd = JavaAutoLauncher.buildCommand(
                "/usr/bin/java", "app.cli.MainCli",
                new String[]{"in.jar", "--output", "out"}, "app.cli.MainCli in.jar");
        assertTrue(cmd.contains("-cp"), "expected -cp relaunch, got: " + cmd);
        assertTrue(cmd.contains("app.cli.MainCli"));
    }

    @Test
    void javapCommandIsNeverNull() {
        assertNotNull(JavaDiscovery.javapCommand());
    }
}
