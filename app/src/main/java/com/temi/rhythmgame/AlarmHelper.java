package com.temi.rhythmgame;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

public class AlarmHelper {

    // 알람을 설정하는 함수 (시, 분을 입력받음)
    public static void setGameAlarm(Context context, int hour, int minute) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // 시간이 되면 'AlarmReceiver'로 배달될 택배(Intent) 준비
        Intent intent = new Intent(context, AlarmReceiver.class);

        // 안드로이드 12(API 31) 이상부터는 FLAG_IMMUTABLE이 필수입니다.
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 사용자가 입력한 시간으로 달력(Calendar) 세팅
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);

        // 만약 설정한 시간이 '오늘 이미 지난 시간'이라면, '내일' 울리도록 하루를 더함
        if (calendar.before(Calendar.getInstance())) {
            calendar.add(Calendar.DATE, 1);
        }

        // 알람 매니저에 예약 접수 (Doze 모드에서도 정각에 깨우도록 세팅)
        if (alarmManager != null) {
            // 안드로이드 12 이상에서 권한 체크 (안전 장치)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
            }
        }
    }

    // [추가] 운동 알람 취소 함수 (requestCode: 0)
    public static void cancelGameAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class);

        // 등록할 때와 동일한 식별자(0)로 PendingIntent 생성
        // FLAG_NO_CREATE: 기존에 생성된 알람이 없다면 새로 만들지 않고 null을 반환하여 안전하게 체크
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );

        if (alarmManager != null && pendingIntent != null) {
            alarmManager.cancel(pendingIntent); // AlarmManager에서 예약 삭제
            pendingIntent.cancel();             // PendingIntent 메모리 해제
            Log.d("AlarmHelper", "운동 알람(0) 취소 완료");
        }
    }
}
