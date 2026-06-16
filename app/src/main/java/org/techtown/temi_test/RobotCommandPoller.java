package org.techtown.temi_test;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RobotCommandPoller {

    private static final String PREFS_NAME = "robot_command_prefs";
    private static final String KEY_LAST_RECEIVED = "last_received_command_id";
    private static final String KEY_LAST_COMPLETED = "last_completed_command_id";

    public interface Listener {
        void onCommand(RobotApiClient.RobotCommand command);

        void onPollingError(String message);
    }

    private final String robotId;
    private final RobotApiClient apiClient;
    private final SharedPreferences preferences;
    private final Listener listener;
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public RobotCommandPoller(
            Context context,
            String robotId,
            RobotApiClient apiClient,
            Listener listener
    ) {
        this.robotId = robotId;
        this.apiClient = apiClient;
        this.listener = listener;
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        requestInFlight.set(false);
    }

    public String getLastCompletedCommandId() {
        return preferences.getString(KEY_LAST_COMPLETED, "");
    }

    public void markCompleted(String commandId) {
        preferences.edit()
                .putString(KEY_LAST_COMPLETED, commandId == null ? "" : commandId)
                .apply();
    }

    private void poll() {
        if (!requestInFlight.compareAndSet(false, true)) {
            return;
        }
        String lastReceived = preferences.getString(KEY_LAST_RECEIVED, "");
        apiClient.getCommand(robotId, lastReceived, new RobotApiClient.CommandCallback() {
            @Override
            public void onCommand(RobotApiClient.RobotCommand command) {
                requestInFlight.set(false);
                if (command.commandId.isEmpty() || command.commandId.equals(lastReceived)) {
                    return;
                }
                preferences.edit().putString(KEY_LAST_RECEIVED, command.commandId).apply();
                listener.onCommand(command);
            }

            @Override
            public void onNoCommand() {
                requestInFlight.set(false);
            }

            @Override
            public void onError(String message) {
                requestInFlight.set(false);
                listener.onPollingError(message);
            }
        });
    }
}
