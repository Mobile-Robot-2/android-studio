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

public class AlarmActivity extends BaseActivity {

    private Robot robot;
    private CountDownTimer countDownTimer;
    private TextView tvTimer;
    private RobotStatusHeartbeat heartbeat;

    // 명시적 종료(타이머 만료/닫기)로 busy 해제·배출을 이미 마쳤는지.
    // true 면 onDestroy 안전망이 busy 를 다시 건드리지 않는다(배출된 작업의 busy 보호).
    private boolean taskCompleted = false;

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
        heartbeat = new RobotStatusHeartbeat("MOVING_TO_USER", "거실");
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
                heartbeat.update("RETURNING_TO_BASE", "home base");
                taskCompleted = true;
                CareTaskCoordinator.clearBusy(AlarmActivity.this);
                CareTaskCoordinator.runNextPendingTask(AlarmActivity.this);
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

            taskCompleted = true;
            CareTaskCoordinator.clearBusy(AlarmActivity.this);
            CareTaskCoordinator.runNextPendingTask(AlarmActivity.this);

            Intent intent = new Intent(AlarmActivity.this, StartActivity.class);
            startActivity(intent);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 명시적 종료를 거치지 못하고 파괴된 경우에만 안전망으로 busy 해제 + 대기 작업 배출
        // (startGameAlarm 이 launch 직전 busy 를 걸어두므로, 안 풀면 다음 작업이 막힌다)
        if (!taskCompleted) {
            CareTaskCoordinator.clearBusy(this);
            CareTaskCoordinator.runNextPendingTask(this);
        }
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
