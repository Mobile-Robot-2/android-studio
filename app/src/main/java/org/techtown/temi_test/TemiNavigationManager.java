package org.techtown.temi_test;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;

import java.util.List;

public class TemiNavigationManager implements OnGoToLocationStatusChangedListener {

    public interface Listener {
        void onNavigationStarted(String location);

        void onNavigationCompleted(String location);

        void onNavigationFailed(String location, String message);
    }

    private final Listener listener;
    private Robot robot;
    private boolean listenerRegistered;

    public TemiNavigationManager(Listener listener) {
        this.listener = listener;
        try {
            robot = Robot.getInstance();
        } catch (Exception ignored) {
            robot = null;
        }
    }

    public void start() {
        if (robot != null && !listenerRegistered) {
            robot.addOnGoToLocationStatusChangedListener(this);
            listenerRegistered = true;
        }
    }

    public void stop() {
        if (robot != null && listenerRegistered) {
            robot.removeOnGoToLocationStatusChangedListener(this);
            listenerRegistered = false;
        }
    }

    public boolean isAvailable() {
        return robot != null && robot.isReady();
    }

    public boolean goTo(String location) {
        if (!isAvailable() || location == null || location.trim().isEmpty()) {
            return false;
        }
        List<String> locations = robot.getLocations();
        if (locations == null || !locations.contains(location)) {
            listener.onNavigationFailed(location, "저장되지 않은 위치입니다: " + location);
            return false;
        }
        robot.goTo(location);
        return true;
    }

    public void emergencyStop() {
        if (robot != null) {
            robot.stopMovement();
        }
    }

    @Override
    public void onGoToLocationStatusChanged(
            String location,
            String status,
            int descriptionId,
            String description
    ) {
        if (OnGoToLocationStatusChangedListener.START.equals(status)
                || OnGoToLocationStatusChangedListener.CALCULATING.equals(status)
                || OnGoToLocationStatusChangedListener.GOING.equals(status)) {
            listener.onNavigationStarted(location);
        } else if (OnGoToLocationStatusChangedListener.COMPLETE.equals(status)) {
            listener.onNavigationCompleted(location);
        } else if (OnGoToLocationStatusChangedListener.ABORT.equals(status)) {
            listener.onNavigationFailed(
                    location,
                    description == null || description.isEmpty() ? "이동이 중단되었습니다." : description
            );
        }
    }
}
