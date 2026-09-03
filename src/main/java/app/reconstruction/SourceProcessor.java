package app.reconstruction;

import app.core.DecompileProgressListener;
import app.core.DecompileStats;
import app.mappings.MappingSet;
import app.versions.VersionProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full post-processing pipeline (spec section 8):
 * Raw -&gt; Import Cleanup -&gt; Name Remapping -&gt; Synthetic Cleanup -&gt;
 * Formatting -&gt; Package Reconstruction -&gt; Validation -&gt; Final.
 * Name remapping runs before import cleanup so renamed imports get sorted too.
 */
public final class SourceProcessor {

    public record ProcessedSources(Map<String, String> sources, int renamedClasses, int memberHits) {
    }

    private SourceProcessor() {
    }

    public static ProcessedSources process(Map<String, String> raw,
                                           MappingSet mappings,
                                           VersionProfile profile,
                                           DecompileStats stats,
                                           DecompileProgressListener listener) {
        VersionProfile.PostProcessing pp = profile == null
                ? VersionProfile.PostProcessing.defaults() : profile.postProcessing();

        listener.onStatus("Remapping names (" + mappingsName(mappings) + ")...");
        NameRemapper.Result remapped = NameRemapper.remap(raw, mappings);
        stats.incMappings(remapped.classesRenamed + remapped.memberHits);

        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int total = Math.max(1, remapped.sourcesByNewInternal.size());
        for (Map.Entry<String, String> e : remapped.sourcesByNewInternal.entrySet()) {
            if (listener.isCancelled()) {
                break;
            }
            String internal = e.getKey();
            boolean mapped = remapped.renamedTargets.contains(internal);
            String src = e.getValue();
            src = SyntheticCleanup.clean(src, pp.addUnmappedMarkers());
            src = ImportCleaner.clean(src);
            src = SourceFormatter.format(src);
            if (pp.addUnmappedMarkers() && !mapped) {
                src = SyntheticCleanup.ensureHeader(src, internal, false);
            }
            List<String> issues = SourceValidator.validate(internal, src);
            for (String issue : issues) {
                stats.addWarning(issue);
                listener.onWarning(issue);
            }
            out.put(internal, src);
            i++;
            if (i % 250 == 0 || i == total) {
                listener.onProgress(0.9 + 0.08 * i / total, internal, i, total);
            }
        }
        return new ProcessedSources(out, remapped.classesRenamed, remapped.memberHits);
    }

    private static String mappingsName(MappingSet m) {
        if (m == null || m.isEmpty()) {
            return "no mappings (synthetic names)";
        }
        return m.size() + " entries";
    }
}
