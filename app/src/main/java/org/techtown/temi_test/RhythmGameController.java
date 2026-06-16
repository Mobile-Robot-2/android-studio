package org.techtown.temi_test;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

public class RhythmGameController {

    public interface FrameCapture {
        void capture(Completion completion);
    }

    public interface Completion {
        void onComplete();
    }

    private static final long FRAME_INTERVAL_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean frameInFlight = new AtomicBoolean(false);
    private final FrameCapture frameCapture;

    public RhythmGameController(FrameCapture frameCapture) {
        this.frameCapture = frameCapture;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            handler.post(frameRunnable);
        }
    }

    public void stop() {
        running.set(false);
        handler.removeCallbacks(frameRunnable);
    }

    public boolean isRunning() {
        return running.get();
    }

    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) {
                return;
            }
            if (frameInFlight.compareAndSet(false, true)) {
                frameCapture.capture(() -> {
                    frameInFlight.set(false);
                    if (running.get()) {
                        handler.postDelayed(frameRunnable, FRAME_INTERVAL_MS);
                    }
                });
            } else {
                handler.postDelayed(this, FRAME_INTERVAL_MS);
            }
        }
    };
}
