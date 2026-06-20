package com.temi.rhythmgame;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * PatrolHelper - 순찰 반복 스케줄러
 *
 * 사용자가 설정한 주기(분)마다 PatrolReceiver를 깨워 순찰을 실행한다.
 * AlarmManager의 반복 알람은 Doze 모드에서 throttling 될 수 있으므로,
 * "한 번 예약 → 울릴 때마다 다음 주기를 다시 예약"하는 자기 재예약 방식을 쓴다.
 * (PatrolReceiver.onReceive 에서 scheduleNext 를 다시 호출)
 */
public class PatrolHelper {

    private static final String TAG = "PatrolHelper";

    // 다른 알람(운동:0, 복약:100)과 겹치지 않는 식별자
    public static final int REQUEST_CODE = 200;

    private static final String PREFS = "patrol_prefs";
    private static final String KEY_INTERVAL_MIN = "interval_min";

    /** 순찰 주기(분)를 저장하고 첫 순찰을 예약한다. intervalMinutes <= 0 이면 순찰을 끈다. */
    public static void setPatrolInterval(Context context, int intervalMinutes) {
        if (intervalMinutes <= 0) {
            cancelPatrol(context);
            return;
        }
        getPrefs(context).edit().putInt(KEY_INTERVAL_MIN, intervalMinutes).apply();
        scheduleNext(context, intervalMinutes);
        Log.d(TAG, "순찰 주기 설정: " + intervalMinutes + "분");
    }

    /** 저장된 순찰 주기(분). 설정 안 됐으면 0. */
    public static int getInterval(Context context) {
        return getPrefs(context).getInt(KEY_INTERVAL_MIN, 0);
    }

    /** 다음 순찰 1회를 intervalMinutes 분 뒤로 예약한다. (PatrolReceiver 가 매 사이클마다 호출) */
    public static void scheduleNext(Context context, int intervalMinutes) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        PendingIntent pendingIntent = buildPendingIntent(
                context, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerAt = System.currentTimeMillis() + (long) intervalMinutes * 60_000L;

        boolean canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || alarmManager.canScheduleExactAlarms();

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } else {
            // 정확 알람 권한이 없으면 비정확 알람으로 대체 (데모 환경 안전장치)
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }

        Log.d(TAG, "다음 순찰 예약: " + intervalMinutes + "분 뒤");
    }

    /** 순찰을 중지한다. (예약 취소 + 저장된 주기 삭제) */
    public static void cancelPatrol(Context context) {
        getPrefs(context).edit().remove(KEY_INTERVAL_MIN).apply();

        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pendingIntent = buildPendingIntent(
                context, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);

        if (alarmManager != null && pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
            Log.d(TAG, "순찰 예약 취소 완료");
        }
    }

    private static PendingIntent buildPendingIntent(Context context, int flags) {
        Intent intent = new Intent(context, PatrolReceiver.class);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
