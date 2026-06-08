package com.temi.rhythmgame;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Calendar;

/**
 * StartActivity - 앱의 시작 화면
 * "리듬게임 시작!" 버튼을 클릭하면 PrepareActivity로 이동합니다.
 * "운동 알람 맞추기" 버튼을 클릭하면 예약 다이얼로그가 뜹니다.
 */
public class StartActivity extends AppCompatActivity {

    private static final String TAG = "StartActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);
        Log.d(TAG, "StartActivity 생성됨");

        // 1. 기존 게임 시작 버튼
        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> {
            Log.d(TAG, "시작 버튼 클릭 → PrepareActivity로 전환");
            startActivity(new Intent(StartActivity.this, PrepareActivity.class));
        });

        // 2. 기존 운동 알람 설정 버튼
        Button btnSetAlarm = findViewById(R.id.btnSetAlarm);
        btnSetAlarm.setOnClickListener(v -> {
            Log.d(TAG, "알람 설정 버튼 클릭 → 시간 팝업 호출");
            showTimePickerDialog();
        });

        // 3. 복약 알람 설정 버튼
        Button btnSetMedicationAlarm =
                findViewById(R.id.btnSetMedicationAlarm);

        btnSetMedicationAlarm.setOnClickListener(v -> {
            Log.d(TAG, "복약 알람 설정 버튼 클릭");
            showMedicationTimePickerDialog();
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
}