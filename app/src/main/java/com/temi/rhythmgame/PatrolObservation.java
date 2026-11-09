package com.temi.rhythmgame;

/** Tracks whether an observation can be reported as clear. */
final class PatrolObservation {
    static final long RESPONSE_TIMEOUT_MS = 5000;
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private long lastFrameAt;
    private boolean pendingFrame;
    private boolean reliablePoseSeen;
    private boolean incomplete;
    private int consecutiveFailures;

    PatrolObservation(long now) {
        lastFrameAt = now;
    }

    void frameSent(long now) {
        lastFrameAt = now;
        pendingFrame = true;
    }

    void response(long now, boolean reliablePose) {
        pendingFrame = false;
        consecutiveFailures = 0;
        reliablePoseSeen |= reliablePose;
        incomplete |= !reliablePose;
    }

    void failure() {
        pendingFrame = false;
        consecutiveFailures++;
        incomplete = true;
    }

    boolean serverFailed(long now) {
        return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
                || (pendingFrame && now - lastFrameAt >= RESPONSE_TIMEOUT_MS);
    }

    boolean cameraUnavailable(long now) {
        return !pendingFrame && now - lastFrameAt >= RESPONSE_TIMEOUT_MS;
    }

    boolean isClear() {
        return reliablePoseSeen && !incomplete;
    }
}
