package com.temi.rhythmgame;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.SttLanguage;
import com.robotemi.sdk.TtsRequest;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * StartActivity - 앱의 시작 화면
 * "리듬게임 시작!" 버튼을 클릭하면 PrepareActivity로 이동합니다.
 * "운동 알람 맞추기" 버튼을 클릭하면 예약 다이얼로그가 뜹니다.
 * "알람 취소" 버튼을 클릭하면 예약된 모든 알람을 해제합니다.
 */
public class StartActivity extends AppCompatActivity {

    private static final String TAG = "StartActivity";
    private RobotApiClient robotApiClient;
    private Handler serverHandler;
    private boolean controlLaunchInProgress = false;

    private Robot robot;

    // AsrListener 등록 시 SDK가 기본 NLP를 우회하므로, 약 관련 아닌 명령은 startDefaultNlu로 재전달
    private final Robot.AsrListener asrListener = (asrResult, sttLanguage) -> {
        if (asrResult == null) return;
        if (asrResult.contains("약") && (asrResult.contains("먹었") || asrResult.contains("복약"))) {
            respondToMedicationQuery();
        } else {
            robot.startDefaultNlu(asrResult, sttLanguage);
        }
    };

    private final Runnable statusHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            postIdleStatus();
            serverHandler.postDelayed(this, 2000);
        }
    };

    private final Runnable commandWatchRunnable = new Runnable() {
        @Override
        public void run() {
            watchForServerCommand();
            serverHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);
        robotApiClient = new RobotApiClient(ServerConfig.BASE_URL);
        serverHandler = new Handler(Looper.getMainLooper());

        // ⭐️ 배경 애니메이션 실행 코드
        ConstraintLayout rootLayout = findViewById(R.id.rootLayout);
        AnimationDrawable animationDrawable = (AnimationDrawable) rootLayout.getBackground();

        // 자연스럽게 번지도록 페이드 인/아웃 시간을 2초/4초로 넉넉하게 부여
        animationDrawable.setEnterFadeDuration(2000);
        animationDrawable.setExitFadeDuration(4000);
        animationDrawable.start();

        robot = Robot.getInstance();
        robot.addAsrListener(asrListener);
        Log.d(TAG, "StartActivity 생성됨");

        // 1. 기존 게임 시작 버튼
        // 1-1. 영상 1번 시작 버튼
        Button btnStart1 = findViewById(R.id.btnStart1);
        btnStart1.setOnClickListener(v -> {
            Log.d(TAG, "영상 1번 클릭 → PrepareActivity로 전환");
            Intent intent = new Intent(StartActivity.this, PrepareActivity.class);
            intent.putExtra("videoType", 1); // ⭐️ 1번 영상이라는 이름표 달기
            intent.putExtra("RESET_GAME", true);
            startActivity(intent);
        });

        // 1-2. 영상 2번 시작 버튼
        Button btnStart2 = findViewById(R.id.btnStart2);
        btnStart2.setOnClickListener(v -> {
            Log.d(TAG, "영상 2번 클릭 → PrepareActivity로 전환");
            Intent intent = new Intent(StartActivity.this, PrepareActivity.class);
            intent.putExtra("videoType", 2); // ⭐️ 2번 영상이라는 이름표 달기
            intent.putExtra("RESET_GAME", true);
            startActivity(intent);
        });

        // 2. 기존 운동 알람 설정 버튼
        Button btnSetAlarm = findViewById(R.id.btnSetAlarm);
        btnSetAlarm.setOnClickListener(v -> {
            Log.d(TAG, "알람 설정 버튼 클릭 → 시간 팝업 호출");
            showTimePickerDialog();
        });

        // 3. 복약 알람 설정 버튼
        Button btnSetMedicationAlarm = findViewById(R.id.btnSetMedicationAlarm);
        btnSetMedicationAlarm.setOnClickListener(v -> {
            Log.d(TAG, "복약 알람 설정 버튼 클릭");
            showMedicationTimePickerDialog();
        });

        // ⭐️ 4. [추가] 모든 알람 취소 버튼 연결
        Button btnCancelAllAlarms = findViewById(R.id.btnCancelAllAlarms);
        btnCancelAllAlarms.setOnClickListener(v -> {
            Log.d(TAG, "모든 알람 취소 버튼 클릭");

            // 운동 알람 취소 (requestCode: 0)
            AlarmHelper.cancelGameAlarm(StartActivity.this);

            // 복약 알람 취소 (requestCode: 100)
            MedicationHelper.cancelMedicationAlarm(StartActivity.this);

            // 사용자 알림 피드백
            Toast.makeText(StartActivity.this, "설정된 모든 알람이 취소되었습니다.", Toast.LENGTH_SHORT).show();
        });

        // 5. 순찰 주기 설정 버튼
        Button btnSetPatrol = findViewById(R.id.btnSetPatrol);
        btnSetPatrol.setOnClickListener(v -> {
            Log.d(TAG, "순찰 주기 설정 버튼 클릭");
            showPatrolIntervalDialog();
        });
    }

    // 순찰 주기(분) 선택 다이얼로그
    private void showPatrolIntervalDialog() {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(1);
        picker.setMaxValue(60);

        // 기존 설정값이 있으면 그 값을, 없으면 10분을 기본 선택
        int current = PatrolHelper.getInterval(this);
        picker.setValue(current > 0 ? current : 10);

        new AlertDialog.Builder(this)
                .setTitle("순찰 주기 설정 (분)")
                .setMessage(current > 0
                        ? "현재 " + current + "분마다 순찰 중입니다."
                        : "순찰이 꺼져 있습니다.")
                .setView(picker)
                .setPositiveButton("설정", (dialog, which) -> {
                    int minutes = picker.getValue();
                    PatrolHelper.setPatrolInterval(StartActivity.this, minutes);
                    Toast.makeText(
                            StartActivity.this,
                            minutes + "분마다 순찰을 시작합니다.",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .setNeutralButton("순찰 끄기", (dialog, which) -> {
                    PatrolHelper.cancelPatrol(StartActivity.this);
                    Toast.makeText(
                            StartActivity.this,
                            "순찰을 종료했습니다.",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        controlLaunchInProgress = false;
        serverHandler.post(statusHeartbeatRunnable);
        serverHandler.post(commandWatchRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        serverHandler.removeCallbacks(statusHeartbeatRunnable);
        serverHandler.removeCallbacks(commandWatchRunnable);
    }

    private void respondToMedicationQuery() {
        SharedPreferences prefs = getSharedPreferences("medication_prefs", MODE_PRIVATE);
        String status = prefs.getString("status", "UNKNOWN");
        String date = prefs.getString("date", "");
        String alarmTime = prefs.getString("alarm_time", "");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date());

        String response;
        if (!today.equals(date)) {
            response = "오늘 복약 기록이 아직 없습니다.";
        } else if ("TAKEN".equals(status)) {
            response = "네, 오늘 약을 드셨습니다.";
        } else if ("NO_RESPONSE".equals(status)) {
            response = formatNoResponseMessage(alarmTime);
        } else {
            response = "오늘 복약 기록을 찾을 수 없습니다.";
        }

        if (robot != null) {
            robot.speak(TtsRequest.create(response, false));
        }
    }

    private String formatNoResponseMessage(String alarmTime) {
        if (alarmTime == null || alarmTime.isEmpty()) {
            return "아니요, 복약 확인이 되지 않습니다. 약을 드셔야할 것 같아요!";
        }
        try {
            String[] parts = alarmTime.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            String ampm = h < 12 ? "오전" : "오후";
            int h12 = h % 12;
            if (h12 == 0) h12 = 12;
            String timeLabel = m == 0
                    ? ampm + " " + h12 + "시"
                    : ampm + " " + h12 + "시 " + m + "분";
            return timeLabel + "에 드셔야 하는 약을 드시지 않았습니다. 약을 드셔야할 것 같아요!";
        } catch (Exception e) {
            return "아니요, 복약 확인이 되지 않습니다. 약을 드셔야할 것 같아요!";
        }
    }

    private void postIdleStatus() {
        try {
            JSONObject status = new JSONObject();
            status.put("robot_id", ServerConfig.ROBOT_ID);
            status.put("state", "IDLE_AT_BASE");
            status.put("location", "home base");
            status.put("battery", JSONObject.NULL);
            status.put("active_command_id", JSONObject.NULL);
            status.put("last_completed_command_id", JSONObject.NULL);
            status.put("command_status", JSONObject.NULL);
            status.put("last_error", JSONObject.NULL);
            status.put("status_result", JSONObject.NULL);

            robotApiClient.postStatus(status, new RobotApiClient.JsonCallback() {
                @Override
                public void onSuccess(JSONObject json) {
                    Log.d(TAG, "Idle heartbeat sent");
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "Idle heartbeat failed: " + e.getMessage());
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Idle status JSON failed: " + e.getMessage());
        }
    }

    private void watchForServerCommand() {
        if (controlLaunchInProgress) {
            return;
        }

        String lastCommandId = getSharedPreferences("robot_command_state", MODE_PRIVATE)
                .getString("last_received_command_id", null);
        robotApiClient.getCommand(ServerConfig.ROBOT_ID, lastCommandId, new RobotApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject json) {
                if (!json.optBoolean("has_command", false)) {
                    return;
                }

                controlLaunchInProgress = true;
                Intent intent = new Intent(StartActivity.this, MainActivity.class);
                intent.putExtra("CONTROL_MODE", true);
                startActivity(intent);
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Command watch failed: " + e.getMessage());
            }
        });
    }

    // 운동 알람 시간 선택
    private void showTimePickerDialog() {

        Calendar calendar = Calendar.getInstance();

        int currentHour =
                calendar.get(Calendar.HOUR_OF_DAY);

        int currentMinute =
                calendar.get(Calendar.MINUTE);

        TimePickerDialog timePickerDialog =
                new TimePickerDialog(
                        this,
                        (view, selectedHour, selectedMinute) -> {

                            Log.d(
                                    TAG,
                                    "설정된 알람 시간 - "
                                            + selectedHour
                                            + "시 "
                                            + selectedMinute
                                            + "분"
                            );

                            AlarmHelper.setGameAlarm(
                                    StartActivity.this,
                                    selectedHour,
                                    selectedMinute
                            );

                            String timeText =
                                    selectedHour
                                            + "시 "
                                            + selectedMinute
                                            + "분에 알람이 설정되었습니다.";

                            Toast.makeText(
                                    StartActivity.this,
                                    timeText,
                                    Toast.LENGTH_SHORT
                            ).show();
                        },
                        currentHour,
                        currentMinute,
                        false
                );

        timePickerDialog.show();
    }

    // 복약 알람 시간 선택
    private void showMedicationTimePickerDialog() {

        Calendar calendar = Calendar.getInstance();

        int currentHour =
                calendar.get(Calendar.HOUR_OF_DAY);

        int currentMinute =
                calendar.get(Calendar.MINUTE);

        TimePickerDialog timePickerDialog =
                new TimePickerDialog(
                        this,
                        (view, selectedHour, selectedMinute) -> {

                            Log.d(
                                    TAG,
                                    "복약 알람 설정 - "
                                            + selectedHour
                                            + "시 "
                                            + selectedMinute
                                            + "분"
                            );

                            MedicationHelper.setMedicationAlarm(
                                    StartActivity.this,
                                    selectedHour,
                                    selectedMinute
                            );

                            String timeText =
                                    selectedHour
                                            + "시 "
                                            + selectedMinute
                                            + "분에 복약 알람이 설정되었습니다.";

                            Toast.makeText(
                                    StartActivity.this,
                                    timeText,
                                    Toast.LENGTH_SHORT
                            ).show();
                        },
                        currentHour,
                        currentMinute,
                        false
                );

        timePickerDialog.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (robot != null) robot.removeAsrListener(asrListener);
    }
}
