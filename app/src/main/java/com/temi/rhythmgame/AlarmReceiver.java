package com.temi.rhythmgame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("AlarmReceiver", "알람 신호 수신! 테미 화면을 깨웁니다.");

        // 직접 startActivity 하지 않고 조정자를 거친다.
        // 로봇이 순찰/복약 등으로 바쁘면 게임 알람은 pending 으로 미뤄졌다가
        // 그 작업이 끝난 뒤 실행된다. (겹침 방지)
        CareTaskCoordinator.requestGame(context);
    }
}
