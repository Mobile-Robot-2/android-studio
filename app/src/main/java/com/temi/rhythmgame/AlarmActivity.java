package com.temi.rhythmgame;

import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

public class AlarmActivity extends AppCompatActivity {

    private Robot robot;
    private CountDownTimer countDownTimer;
    private TextView tvTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 잠든 화면 깨우기
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_alarm);

        // ⭐️ 배경 애니메이션 실행 코드
        ConstraintLayout rootLayout = findViewById(R.id.rootLayout);
        if (rootLayout != null && rootLayout.getBackground() instanceof AnimationDrawable) {
            AnimationDrawable animationDrawable = (AnimationDrawable) rootLayout.getBackground();
            animationDrawable.setEnterFadeDuration(2000);
            animationDrawable.setExitFadeDuration(4000);
            animationDrawable.start();
        }

        robot = Robot.getInstance();
        tvTimer = findViewById(R.id.tvTimer);

        // [추가] 1. 홈 베이스에서 분리되어 어르신이 계신 지정된 장소로 이동
        // 테미 기기에 저장해 둔 위치 이름(예: "거실 소파", "아버지 방")을 정확히 입력하세요.
        robot.goTo("거실");

        // [추가] 2. 이동하면서 주변 사용자를 찾아 시선을 맞추도록 설정 (Face Tracking)
        robot.setTrackUserOn(true);

        // 3. 음성 안내
        robot.speak(TtsRequest.create("리듬 게임을 시작할 시간입니다! 화면의 시작 버튼을 눌러주세요.", false));

        // 60초 타이머 시작
        countDownTimer = new CountDownTimer(60000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                tvTimer.setText(secondsLeft + "초 후 자동 복귀합니다.");
            }

            @Override
            public void onFinish() {
                robot.setTrackUserOn(false);
                robot.stopMovement();
                robot.speak(TtsRequest.create("응답이 확인되지 않아 알람을 종료하고 대기 장소로 복귀합니다.", false));
                robot.goTo("home base");
                finish();
            }
        }.start();

        Button btnDismiss = findViewById(R.id.btnDismiss);
        btnDismiss.setOnClickListener(v -> {
            robot.setTrackUserOn(false);
            robot.stopMovement();

            if (countDownTimer != null) {
                countDownTimer.cancel();
            }

            Intent intent = new Intent(AlarmActivity.this, StartActivity.class);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (robot != null) {
            robot.setTrackUserOn(false);
        }
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}
