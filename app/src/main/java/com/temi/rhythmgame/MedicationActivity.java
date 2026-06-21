package com.temi.rhythmgame;

import android.graphics.drawable.AnimationDrawable;
import android.util.Log;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.UserInfo;
import android.content.Intent;

import java.util.Collections;
import java.util.List;

public class MedicationActivity extends AppCompatActivity {

    private Robot robot; // 주석 해제 완료
    private CountDownTimer countDownTimer;
    private TextView tvMessage;
    private TextView tvTimer;
    private Button btnMedicationDone;
    private RobotStatusHeartbeat heartbeat;

    private boolean isCallLaunched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_medication);

        // ⭐️ 배경 애니메이션 실행 코드
        ConstraintLayout rootLayout = findViewById(R.id.rootLayout);
        if (rootLayout != null && rootLayout.getBackground() instanceof AnimationDrawable) {
            AnimationDrawable animationDrawable = (AnimationDrawable) rootLayout.getBackground();
            animationDrawable.setEnterFadeDuration(2000);
            animationDrawable.setExitFadeDuration(4000);
            animationDrawable.start();
        }

        robot = Robot.getInstance();
        heartbeat = new RobotStatusHeartbeat("MOVING_TO_USER", "거실");

        tvMessage = findViewById(R.id.tvMessage);
        tvTimer = findViewById(R.id.tvTimer);
        btnMedicationDone = findViewById(R.id.btnMedicationDone);

        // 복약 알람 시 어르신이 계신 곳으로 자율 주행 이동
        robot.goTo("거실");
        robot.setTrackUserOn(true);

        tvMessage.setText("복약하실 시간입니다.\n약을 드셨다면 복약 완료 버튼을 눌러주세요.");
        robot.speak(TtsRequest.create("복약하실 시간입니다. 약을 드셨다면 복약 완료 버튼을 눌러주세요.", false));

        countDownTimer = new CountDownTimer(30000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                tvTimer.setText(secondsLeft + "초 후 보호자에게 연락합니다.");
            }

            @Override
            public void onFinish() {
                tvMessage.setText("상태를 확인하고 있습니다.");
                tvTimer.setText("");
                // ⭐️ 1. 먼저 시선 추적을 꺼야 수동으로 고개를 숙일 수 있습니다.
                robot.setTrackUserOn(false);

                // ⭐️ 2. 고개를 숙입니다. (-25도: 바닥 감지용)
                robot.tiltAngle(-25);

                // ⭐️ 3. 안내 음성 출력
                robot.speak(TtsRequest.create("어르신 괜찮으신가요?", false));

                // ⭐️ 4. (중요) 고개가 내려갈 최소한의 시간을 준 뒤 화면을 전환합니다.
                new android.os.Handler().postDelayed(() -> {
                    Intent intent = new Intent(MedicationActivity.this, MainActivity.class);
                    intent.putExtra("fall_mode", true);
                    startActivity(intent);
                    finish();
                }, 1000); // 1초 대기 후 전환
            }
        }.start();

        btnMedicationDone.setOnClickListener(v -> {
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }

            tvMessage.setText("복약이 확인되었습니다.");
            tvTimer.setText("알람 종료");
            robot.speak(TtsRequest.create("복약이 확인되었습니다. 홈 베이스로 복귀합니다.", false));

            robot.setTrackUserOn(false);
            robot.goTo("home base");
            heartbeat.update("RETURNING_TO_BASE", "home base");
            finish();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (heartbeat != null) {
            heartbeat.start();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (heartbeat != null) {
            heartbeat.stop();
        }
    }

    // ────────────────────────────────────────────────────────
    // ⭐️ 3. [추가] 영상통화가 끝나고 다시 화면이 켜졌을 때의 동작
    // ────────────────────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();

        // 영상통화 화면이 닫히고 다시 우리 앱으로 돌아온 상태라면
        if (isCallLaunched) {
            Log.d("MedicationActivity", "보호자 통화 종료 확인 - 홈 베이스 복귀 시작");

            // 다음 알람을 위해 플래그 원상복구
            isCallLaunched = false;

            // 시선 추적 끄고 진짜 충전소로 복귀
            if (robot != null) {
                robot.setTrackUserOn(false);
                robot.goTo("home base");
                heartbeat.update("RETURNING_TO_BASE", "home base");
            }

            // 복귀 명령이 내려졌으니 이제 완전히 앱 종료
            finish();
        }
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
        if (heartbeat != null) {
            heartbeat.stop();
        }
    }
}
