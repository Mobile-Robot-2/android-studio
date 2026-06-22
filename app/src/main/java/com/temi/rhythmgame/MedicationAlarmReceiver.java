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

        int hour = intent.getIntExtra("ALARM_HOUR", -1);
        int minute = intent.getIntExtra("ALARM_MINUTE", -1);

        // 직접 startActivity 하지 않고 조정자를 거친다.
        // 로봇이 순찰/게임 등으로 바쁘면 복약 알람은 시각과 함께 pending 으로 미뤄졌다가
        // 그 작업이 끝난 뒤 실행된다. (겹침 방지 / 복약 알람 유실 방지)
        CareTaskCoordinator.requestMedication(context, hour, minute);
    }
}