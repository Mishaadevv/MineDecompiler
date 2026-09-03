package app.mappings;

import app.core.GameVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-detecting file provider: inspects a user-supplied mappings file
 * (any supported format) or every file inside a directory, and merges them.
 * Also supports trivial {@code *.properties} custom mappings
 * ({@code obf.internal.Name=named.internal.Name}).
 */
public final class FileMappingProvider implements MappingProvider {

    private final Path path;

    public FileMappingProvider(Path path) {
        this.path = path;
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public MappingSet loadMappings(GameVersion version) throws Exception {
        MappingSet out = new MappingSet();
        out.putMetadata("provider", "file");
        if (path == null) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
        } else if (Files.isRegularFile(path)) {
            files.add(path);
        }
        for (Path f : files) {
            try {
                MappingSet one = loadSingle(f, version);
                out.putAll(one);
            } catch (Exception e) {
                out.putMetadata("skip:" + f.getFileName(), e.getMessage());
            }
        }
        out.putMetadata("source", path.toString());
        return out;
    }

    private MappingSet loadSingle(Path f, GameVersion version) throws Exception {
        String lower = f.getFileName().toString().toLowerCase();
        if (lower.endsWith(".properties")) {
            return loadProperties(f);
        }
        if (TinyMappingProvider.looksLikeTiny(f)) {
            return new TinyMappingProvider(f).loadMappings(version);
        }
        if (SrgMappingProvider.looksLikeSrg(f)) {
            return new SrgMappingProvider(f).loadMappings(version);
        }
        if (MojangMappingProvider.looksLikeMojang(f)) {
            return new MojangMappingProvider(f).loadMappings(version);
        }
        // Default attempt: Mojang ProGuard (most common), then SRG.
        try {
            MappingSet m = new MappingSet();
            MojangMappingProvider.parseProGuard(f, m);
            if (!m.isEmpty()) {
                return m;
            }
        } catch (Exception ignored) {
        }
        MappingSet m = new MappingSet();
        SrgMappingProvider.parseSrg(f, m);
        return m;
    }

    private MappingSet loadProperties(Path f) throws IOException {
        MappingSet out = new MappingSet();
        try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String obf = line.substring(0, eq).trim().replace('.', '/');
                String named = line.substring(eq + 1).trim().replace('.', '/');
                out.mapClass(obf, named);
            }
        }
        return out;
    }
}
