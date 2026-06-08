package com.temi.rhythmgame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class MedicationAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        Log.d(
                "MedicationAlarmReceiver",
                "복약 알람 수신"
        );

        Intent alarmIntent =
                new Intent(
                        context,
                        MedicationActivity.class
                );

        alarmIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
        );

        context.startActivity(alarmIntent);
    }
}