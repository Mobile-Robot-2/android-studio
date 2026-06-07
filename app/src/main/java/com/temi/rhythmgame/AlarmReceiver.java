package com.temi.rhythmgame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("AlarmReceiver", "알람 신호 수신! 테미 화면을 깨웁니다.");

        // 신호를 받자마자 AlarmActivity(우리가 곧 만들 알람 화면)를 실행할 준비
        Intent alarmIntent = new Intent(context, AlarmActivity.class);

        // 백그라운드(Service나 Receiver)에서 화면(Activity)을 띄우려면 이 플래그가 무조건 필요합니다.
        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // 알람 화면 켜기!
        context.startActivity(alarmIntent);
    }
}
