package app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Outcome of automatic version detection: best guess + ranked candidates. */
public final class VersionDetectionResult {

    private final GameVersion best;
    private final double confidence;
    private final List<String> candidates;
    private final List<String> evidence;
    private final boolean userOverride;

    public VersionDetectionResult(GameVersion best, double confidence,
                                  List<String> candidates, List<String> evidence,
                                  boolean userOverride) {
        this.best = best;
        this.confidence = confidence;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        this.userOverride = userOverride;
    }

    public GameVersion getBest() {
        return best;
    }

    public double getConfidence() {
        return confidence;
    }

    public List<String> getCandidates() {
        return candidates;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public boolean isUserOverride() {
        return userOverride;
    }

    public boolean isConfident() {
        return confidence >= 0.6 || userOverride;
    }
}
