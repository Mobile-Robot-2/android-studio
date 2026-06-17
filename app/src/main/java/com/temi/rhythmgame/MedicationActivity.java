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

import java.util.Collections;
import java.util.List;

public class MedicationActivity extends AppCompatActivity {

    private Robot robot; // 주석 해제 완료
    private CountDownTimer countDownTimer;
    private TextView tvMessage;
    private TextView tvTimer;
    private Button btnMedicationDone;

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

        tvMessage = findViewById(R.id.tvMessage);
        tvTimer = findViewById(R.id.tvTimer);
        btnMedicationDone = findViewById(R.id.btnMedicationDone);

        // 복약 알람 시 어르신이 계신 곳으로 자율 주행 이동
        robot.goTo("거실");
        robot.setTrackUserOn(true);

        tvMessage.setText("복약하실 시간입니다.\n약을 드셨다면 복약 완료 버튼을 눌러주세요.");
        robot.speak(TtsRequest.create("복약하실 시간입니다. 약을 드셨다면 복약 완료 버튼을 눌러주세요.", false));

        countDownTimer = new CountDownTimer(60000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                tvTimer.setText(secondsLeft + "초 후 보호자에게 연락합니다.");
            }

            @Override
            public void onFinish() {
                Log.d("MedicationActivity", "60초 경과 - 보호자 영상통화 실행 예정");

                tvMessage.setText("응답이 없어 보호자에게 연락합니다.");
                tvTimer.setText("영상통화 연결 중...");
                robot.speak(TtsRequest.create("응답이 없어 보호자에게 긴급 연락을 시도합니다.", false));

                // 🚨 [수정] 강제 리스트화(Collections.singletonList) 시 앱이 튕길 수 있어 안전한 단일 객체 호출로 복구했습니다.
                UserInfo adminInfo = robot.getAdminInfo();

                if (adminInfo != null) {
                    String targetName = adminInfo.getName();
                    String targetUserId = adminInfo.getUserId();

                    // ⭐️ 1. 영상통화가 시작됨을 플래그에 기록
                    isCallLaunched = true;

                    // 모바일 플랫폼으로 명시하여 즉시 전화 연결
                    robot.startTelepresence(targetName, targetUserId, com.robotemi.sdk.constants.Platform.MOBILE);

                    // ⭐️ 2. 기존에 있던 finish(); 삭제 -> 앱이 죽지 않고 백그라운드에 대기합니다!
                } else {
                    Log.e("MedicationActivity", "등록된 보호자 없음");
                    robot.speak(TtsRequest.create("연결할 보호자 연락처가 없습니다. 홈 베이스로 복귀합니다.", false));

                    // 보호자가 없을 때만 바로 복귀 후 종료
                    robot.setTrackUserOn(false);
                    robot.goTo("home base");
                    finish();
                }
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
            finish();
        });
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
    }
}