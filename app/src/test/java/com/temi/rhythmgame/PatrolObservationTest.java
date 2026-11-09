package com.temi.rhythmgame;

import org.junit.Test;
import static org.junit.Assert.*;

public class PatrolObservationTest {
    @Test public void onlyCompleteReliableObservationsAreClear() {
        PatrolObservation observation = new PatrolObservation(0);
        assertFalse(observation.isClear());
        observation.frameSent(0);
        observation.response(500, true);
        assertTrue(observation.isClear());
        observation.response(1000, false);
        assertFalse(observation.isClear());
        assertFalse(observation.serverFailed(1000));
    }

    @Test public void failuresAndTimeoutDoNotBecomeClear() {
        PatrolObservation observation = new PatrolObservation(0);
        observation.frameSent(0);
        observation.response(500, true);
        observation.failure();
        observation.failure();
        assertFalse(observation.serverFailed(2000));
        observation.failure();
        assertTrue(observation.serverFailed(2000));
        assertFalse(observation.isClear());
        PatrolObservation timeout = new PatrolObservation(0);
        timeout.frameSent(0);
        assertTrue(timeout.serverFailed(5000));
        assertFalse(timeout.cameraUnavailable(5000));
    }

    @Test public void validResponseResetsFailuresButPreservesObservationGap() {
        PatrolObservation observation = new PatrolObservation(0);
        observation.frameSent(0);
        observation.failure();
        observation.response(500, true);
        observation.failure();
        assertFalse(observation.serverFailed(1000));
        assertFalse(observation.isClear());
    }

    @Test public void missingCameraFramesAreNotCalledServerFailure() {
        PatrolObservation observation = new PatrolObservation(0);
        assertTrue(observation.cameraUnavailable(5000));
        assertFalse(observation.serverFailed(5000));
        assertFalse(observation.isClear());
    }
}
