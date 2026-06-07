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

        // 2. 새로 추가한 알람 설정 버튼
        Button btnSetAlarm = findViewById(R.id.btnSetAlarm);
        btnSetAlarm.setOnClickListener(v -> {
            Log.d(TAG, "알람 설정 버튼 클릭 → 시간 팝업 호출");
            showTimePickerDialog();
        });
    }

    // 안드로이드 기본 시간 선택기(시계 모양 팝업)를 띄우는 함수
    private void showTimePickerDialog() {
        // 현재 시간을 기본값으로 세팅
        Calendar calendar = Calendar.getInstance();
        int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        int currentMinute = calendar.get(Calendar.MINUTE);

        // 시간 선택 팝업창 생성
        TimePickerDialog timePickerDialog = new TimePickerDialog(
                this,
                (view, selectedHour, selectedMinute) -> {
                    // 사용자가 "확인"을 누르면 알람 등록 진행
                    Log.d(TAG, "설정된 알람 시간 - " + selectedHour + "시 " + selectedMinute + "분");

                    // 만들어둔 AlarmHelper를 통해 알람 시스템에 등록
                    AlarmHelper.setGameAlarm(StartActivity.this, selectedHour, selectedMinute);

                    // 화면에 안내 메시지(Toast) 출력
                    String timeText = selectedHour + "시 " + selectedMinute + "분에 알람이 설정되었습니다.";
                    Toast.makeText(StartActivity.this, timeText, Toast.LENGTH_SHORT).show();
                },
                currentHour,
                currentMinute,
                false // 24시간제(true) 대신 AM/PM(false) 형식 사용
        );

        // 팝업창 화면에 표시
        timePickerDialog.show();
    }
}
