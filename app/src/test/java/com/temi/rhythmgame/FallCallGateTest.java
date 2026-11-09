package com.temi.rhythmgame;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FallCallGateTest {
    @Test public void requiresConsecutiveConfirmationsAndCallsOnlyOnce() {
        FallCallGate gate = new FallCallGate();
        assertEquals(1, gate.observe(true));
        assertEquals(0, gate.observe(false));
        assertEquals(1, gate.observe(true));
        assertEquals(2, gate.observe(true));
        assertEquals(0, gate.observe(true));
        assertEquals(0, gate.observe(false));
        assertEquals(0, gate.observe(true));
    }
}
