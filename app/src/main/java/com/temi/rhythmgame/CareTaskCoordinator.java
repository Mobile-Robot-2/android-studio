package com.temi.rhythmgame;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CareTaskCoordinator - 로봇 작업 조정자
 *
 * 테미는 한 번에 한 가지 작업만 할 수 있다(이동/대화/카메라가 공유 자원).
 * 순찰 / 운동(게임) 알람 / 복약 알람이 시간상 겹치지 않도록 단일 진입점에서 조정한다.
 *
 * 규칙:
 *  - 로봇이 비어 있으면(busy 아님 + 순찰 중 아님) 요청을 즉시 실행한다.
 *  - 바쁘면 작업을 pending(대기)으로 보관했다가, 현재 작업이 끝나는 시점에
 *    runNextPendingTask 가 "한 번에 하나씩" 꺼내 실행한다. → 대기 작업끼리도 겹치지 않는다.
 *  - 순찰 도중 들어온 또 다른 순찰 요청은 큐에 쌓지 않고 버린다(놓친 주기는 건너뜀).
 *
 * 대기 작업이 여러 개일 때 우선순위: 복약 > 운동(게임) > 순찰.
 */
public final class CareTaskCoordinator {
    private static final String TAG = "CareTaskCoordinator";
    private static final String PREFS = "care_task_state";

    private static final String KEY_BUSY = "busy";
    private static final String KEY_BUSY_REASON = "busy_reason";

    private static final String KEY_PENDING_PATROL = "pending_patrol";
    private static final String KEY_PENDING_GAME = "pending_game";
    private static final String KEY_PENDING_MEDICATION = "pending_medication";
    private static final String KEY_PENDING_MED_HOUR = "pending_med_hour";
    private static final String KEY_PENDING_MED_MINUTE = "pending_med_minute";

    /**
     * 순찰이 현재 진행 중인지 추적하는 플래그.
     * SharedPreferences가 아닌 프로세스 내 정적 값으로 둔다 — 순찰 도중 앱 프로세스가
     * 죽으면 자동으로 false 로 리셋되어 "플래그가 stuck 되어 순찰이 영영 안 도는" 데드락을
     * 피한다. (각 Receiver 와 Activity 는 동일 프로세스에서 동작하므로 공유 가능.)
     */
    private static final AtomicBoolean PATROL_IN_PROGRESS = new AtomicBoolean(false);

    private CareTaskCoordinator() {
    }

    // ───────────── busy / 순찰 진행 상태 ─────────────

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

    /** PatrolActivity 가 시작/종료 시 호출하여 순찰 진행 상태를 알린다. */
    public static void setPatrolInProgress(boolean inProgress) {
        PATROL_IN_PROGRESS.set(inProgress);
        Log.d(TAG, "Patrol in progress: " + inProgress);
    }

    /** 로봇이 새 작업을 즉시 수행할 수 있는 상태인지. (바쁘지 않고 순찰 중도 아님) */
    private static boolean isRobotFree(Context context) {
        if (PATROL_IN_PROGRESS.get()) {
            return false;
        }
        return !prefs(context).getBoolean(KEY_BUSY, false);
    }

    // ───────────── 작업 요청 (각 Receiver 의 단일 진입점) ─────────────

    /** 순찰 요청. 순찰 중이면 버리고, 다른 작업 중이면 대기시킨다. */
    public static boolean requestPatrol(Context context) {
        // 이미 순찰 중이면 이번 요청은 그냥 버린다 (중복 실행/겹침 방지).
        // 큐에 쌓지 않는다 — 순찰 진행 중에 들어온 주기는 건너뛰는 것이 맞다.
        if (PATROL_IN_PROGRESS.get()) {
            Log.d(TAG, "Patrol already in progress - skipping duplicate request");
            return false;
        }
        if (prefs(context).getBoolean(KEY_BUSY, false)) {
            prefs(context).edit().putBoolean(KEY_PENDING_PATROL, true).apply();
            Log.d(TAG, "Patrol deferred while busy");
            return false;
        }
        startPatrol(context);
        return true;
    }

    /** 운동(게임) 알람 요청. 로봇이 바쁘면 대기시킨다. */
    public static boolean requestGame(Context context) {
        if (!isRobotFree(context)) {
            prefs(context).edit().putBoolean(KEY_PENDING_GAME, true).apply();
            Log.d(TAG, "Game alarm deferred - robot busy");
            return false;
        }
        startGameAlarm(context);
        return true;
    }

    /** 복약 알람 요청. 로봇이 바쁘면 알람 시각과 함께 대기시킨다. */
    public static boolean requestMedication(Context context, int hour, int minute) {
        if (!isRobotFree(context)) {
            prefs(context).edit()
                    .putBoolean(KEY_PENDING_MEDICATION, true)
                    .putInt(KEY_PENDING_MED_HOUR, hour)
                    .putInt(KEY_PENDING_MED_MINUTE, minute)
                    .apply();
            Log.d(TAG, "Medication alarm deferred - robot busy");
            return false;
        }
        startMedicationAlarm(context, hour, minute);
        return true;
    }

    // ───────────── 대기 작업 배출 ─────────────

    /**
     * 현재 작업이 끝나는 시점(clearBusy / 순찰 종료)마다 호출한다.
     * 로봇이 비어 있으면 대기 작업을 우선순위(복약>게임>순찰)대로 "딱 하나만" 실행한다.
     * 실행된 작업이 다시 busy/순찰중을 표시하므로, 그 다음 대기 작업은 그 작업이 끝날 때
     * 또 한 번 이 메서드가 불려 실행된다 → 대기 작업끼리도 절대 겹치지 않는다.
     */
    public static boolean runNextPendingTask(Context context) {
        if (!isRobotFree(context)) {
            return false; // 아직 뭔가 실행 중 - 그 작업이 끝날 때 다시 호출된다
        }
        SharedPreferences sp = prefs(context);

        if (sp.getBoolean(KEY_PENDING_MEDICATION, false)) {
            int hour = sp.getInt(KEY_PENDING_MED_HOUR, -1);
            int minute = sp.getInt(KEY_PENDING_MED_MINUTE, -1);
            sp.edit().putBoolean(KEY_PENDING_MEDICATION, false).apply();
            startMedicationAlarm(context, hour, minute);
            Log.d(TAG, "Pending medication started");
            return true;
        }
        if (sp.getBoolean(KEY_PENDING_GAME, false)) {
            sp.edit().putBoolean(KEY_PENDING_GAME, false).apply();
            startGameAlarm(context);
            Log.d(TAG, "Pending game started");
            return true;
        }
        if (sp.getBoolean(KEY_PENDING_PATROL, false)) {
            sp.edit().putBoolean(KEY_PENDING_PATROL, false).apply();
            startPatrol(context);
            Log.d(TAG, "Pending patrol started");
            return true;
        }
        return false;
    }

    /** care_task_state 의 pending 순찰 플래그를 비운다. (순찰 끄기 시 호출) */
    public static void clearPendingPatrol(Context context) {
        prefs(context).edit().putBoolean(KEY_PENDING_PATROL, false).apply();
        Log.d(TAG, "Pending patrol cleared");
    }

    // ───────────── 실제 실행 ─────────────

    private static void startPatrol(Context context) {
        // 실제 launch 시점에 진행 플래그를 올린다. (PatrolActivity.onDestroy 에서 내림.)
        // startActivity 와 onCreate 사이의 짧은 틈에 또 다른 작업이 끼어드는 것을 막는다.
        PATROL_IN_PROGRESS.set(true);
        Intent intent = new Intent(context, PatrolActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static void startGameAlarm(Context context) {
        // 실행 직전 busy 를 올려 launch~onCreate 사이 틈에 다른 작업이 끼어드는 것을 막는다.
        setBusy(context, "GAME_ALARM");
        Intent intent = new Intent(context, AlarmActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static void startMedicationAlarm(Context context, int hour, int minute) {
        setBusy(context, "MEDICATION");
        Intent intent = new Intent(context, MedicationActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("ALARM_HOUR", hour);
        intent.putExtra("ALARM_MINUTE", minute);
        context.startActivity(intent);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
