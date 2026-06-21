package com.temi.rhythmgame;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public final class CareTaskCoordinator {
    private static final String TAG = "CareTaskCoordinator";
    private static final String PREFS = "care_task_state";
    private static final String KEY_BUSY = "busy";
    private static final String KEY_BUSY_REASON = "busy_reason";
    private static final String KEY_PENDING_PATROL = "pending_patrol";

    private CareTaskCoordinator() {
    }

    public static void setBusy(Context context, String reason) {
        prefs(context).edit()
                .putBoolean(KEY_BUSY, true)
                .putString(KEY_BUSY_REASON, reason)
                .apply();
        Log.d(TAG, "Busy: " + reason);
    }

    public static void clearBusy(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_BUSY, false)
                .remove(KEY_BUSY_REASON)
                .apply();
        Log.d(TAG, "Busy cleared");
    }

    public static boolean requestPatrol(Context context) {
        SharedPreferences sharedPreferences = prefs(context);
        if (sharedPreferences.getBoolean(KEY_BUSY, false)) {
            String reason = sharedPreferences.getString(KEY_BUSY_REASON, "unknown");
            sharedPreferences.edit().putBoolean(KEY_PENDING_PATROL, true).apply();
            Log.d(TAG, "Patrol deferred while busy: " + reason);
            return false;
        }

        startPatrol(context);
        return true;
    }

    public static boolean runPendingPatrolIfAny(Context context) {
        SharedPreferences sharedPreferences = prefs(context);
        if (!sharedPreferences.getBoolean(KEY_PENDING_PATROL, false)) {
            return false;
        }

        sharedPreferences.edit().putBoolean(KEY_PENDING_PATROL, false).apply();
        startPatrol(context);
        Log.d(TAG, "Pending patrol started");
        return true;
    }

    private static void startPatrol(Context context) {
        Intent intent = new Intent(context, PatrolActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
