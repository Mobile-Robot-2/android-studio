package com.temi.rhythmgame;

final class FallCallGate {
    private int consecutive;
    private boolean called;

    int observe(boolean confirmed) {
        if (called) return 0;
        if (!confirmed) { consecutive = 0; return 0; }
        consecutive++;
        if (consecutive == 1) return 1;
        called = true;
        return 2;
    }
}
