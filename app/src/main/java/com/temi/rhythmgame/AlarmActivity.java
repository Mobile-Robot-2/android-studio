package com.temi.rhythmgame;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

public class AlarmActivity extends AppCompatActivity {

    private Robot robot;
    private CountDownTimer countDownTimer;
    private TextView tvTimer; // 화면의 시간 글씨

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 잠든 화면 강제로 깨우기
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_alarm);

        robot = Robot.getInstance();
        tvTimer = findViewById(R.id.tvTimer); // 타이머 텍스트뷰 연결
        robot.setTrackUserOn(true);

        // 2. 테미 동작 시작
        robot.speak(TtsRequest.create("리듬 게임을 시작할 시간입니다! 화면의 시작 버튼을 눌러주세요.", false));
        // robot.patrol(); // 테미 로봇 내부에 '공간 맵핑(Mapping)'과 '순찰 경로'가 사전에 완벽하게 저장되어 있어야만 정상 작동

        // 3. 60초(60000ms) 타이머 시작, 1초(1000ms)마다 onTick() 실행
        countDownTimer = new CountDownTimer(60000, 1000) {

            // 매 1초마다 실행됨 (화면 글자 갱신)
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                tvTimer.setText(secondsLeft + "초 후 자동 복귀합니다.");
            }

            // 0초가 되면 실행됨 (단호한 복귀)
            @Override
            public void onFinish() {
                robot.setTrackUserOn(false);
                robot.stopMovement();
                robot.speak(TtsRequest.create("응답이 확인되지 않아 알람을 종료하고 대기 장소로 복귀합니다.", false));
                robot.goTo("home base");
                finish();
            }
        }.start();

        // 4. 사용자가 제때 버튼을 눌렀을 때
        Button btnDismiss = findViewById(R.id.btnDismiss);
        btnDismiss.setOnClickListener(v -> {

            robot.setTrackUserOn(false);
            robot.stopMovement();

            // 🚨 타이머 폭탄 해제! (카운트다운 중지)
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }

            // 리듬 게임 화면으로 이동
            Intent intent = new Intent(AlarmActivity.this, StartActivity.class);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 안전장치: 화면이 꺼지면 백그라운드 타이머 확실히 죽이기
        if (robot != null) {
            robot.setTrackUserOn(false);
        }
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}
