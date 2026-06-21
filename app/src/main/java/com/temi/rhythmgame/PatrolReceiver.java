package com.temi.rhythmgame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.util.Log;

public class PatrolReceiver extends BroadcastReceiver {
    private static final String TAG = "PatrolReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Patrol alarm received");

        CareTaskCoordinator.requestPatrol(context);

        int interval = PatrolHelper.getInterval(context);
        if (interval > 0) {
            PatrolHelper.scheduleNext(context, interval);
        }
    }
}
