package com.temi.rhythmgame;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

public class RobotStatusHeartbeat {
    private static final String TAG = "RobotStatusHeartbeat";
    private static final long HEARTBEAT_INTERVAL_MS = 2000L;

    private final RobotApiClient apiClient = new RobotApiClient(ServerConfig.BASE_URL);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String state;
    private String location;
    private String lastError;
    private boolean running;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            postStatus();
            if (running) {
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }
    };

    public RobotStatusHeartbeat(String state, String location) {
        this.state = state;
        this.location = location;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        handler.post(heartbeatRunnable);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(heartbeatRunnable);
    }

    public void update(String state, String location) {
        this.state = state;
        this.location = location;
        postStatus();
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
        postStatus();
    }

    private void postStatus() {
        try {
            JSONObject status = new JSONObject();
            status.put("robot_id", ServerConfig.ROBOT_ID);
            status.put("state", state);
            status.put("location", location);
            status.put("battery", JSONObject.NULL);
            status.put("active_command_id", JSONObject.NULL);
            status.put("last_completed_command_id", JSONObject.NULL);
            status.put("command_status", JSONObject.NULL);
            status.put("last_error", lastError != null ? lastError : JSONObject.NULL);
            status.put("status_result", JSONObject.NULL);

            apiClient.postStatus(status, new RobotApiClient.JsonCallback() {
                @Override
                public void onSuccess(JSONObject json) {
                    Log.d(TAG, "Heartbeat sent: " + state);
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "Heartbeat failed: " + e.getMessage());
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Status JSON failed: " + e.getMessage());
        }
    }
}
