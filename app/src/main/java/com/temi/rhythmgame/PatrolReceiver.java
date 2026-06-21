package com.temi.rhythmgame;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * PatrolReceiver - 순찰 알람 수신기
 *
 * 예약된 시간이 되면 호출된다.
 * 1) PatrolActivity 를 띄워 실제 순찰(거실 → 주방 → 홈베이스)을 실행하고
 * 2) 다음 순찰 주기를 다시 예약한다. (자기 재예약)
 */
public class PatrolReceiver extends BroadcastReceiver {

    private static final String TAG = "PatrolReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "순찰 알람 수신 - 순찰 시작");

        Intent patrolIntent = new Intent(context, PatrolActivity.class);
        // 백그라운드(Receiver)에서 액티비티를 띄우려면 필수
        patrolIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(patrolIntent);

        // 다음 주기 재예약 (순찰이 꺼져 있으면 interval == 0 이라 재예약하지 않음)
        int interval = PatrolHelper.getInterval(context);
        if (interval > 0) {
            PatrolHelper.scheduleNext(context, interval);
        }
    }
}
