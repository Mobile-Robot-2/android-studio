package com.temi.rhythmgame;

import android.util.Log;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

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

        robot = Robot.getInstance(); // 주석 해제 완료

        tvMessage = findViewById(R.id.tvMessage);
        tvTimer = findViewById(R.id.tvTimer);
        btnMedicationDone = findViewById(R.id.btnMedicationDone);

        // [추가] 1. 복약 알람 시에도 어르신이 계신 곳으로 자율 주행 이동
        robot.goTo("거실");

        // [추가] 2. 시선 맞춤 활성화
        robot.setTrackUserOn(true);

        tvMessage.setText("복약하실 시간입니다.\n약을 드셨다면 복약 완료 버튼을 눌러주세요.");

        // 실기기 TTS 주석 해제 완료
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

                // 타임아웃 시 음성 안내
                robot.speak(TtsRequest.create("응답이 없어 보호자에게 긴급 연락을 시도합니다.", false));

                // [추가] 3. 테미에 등록된 마스터 보호자 동적 탐색 및 영상통화 연결 (Deprecated 에러 해결)
                List<UserInfo> adminList = Collections.singletonList(robot.getAdminInfo());
                if (adminList != null && !adminList.isEmpty()) {
                    String targetName = adminList.get(0).getName();
                    String targetUserId = adminList.get(0).getUserId();

                    // 모바일 플랫폼으로 명시하여 즉시 전화 연결
                    robot.startTelepresence(targetName, targetUserId, com.robotemi.sdk.constants.Platform.MOBILE);
                } else {
                    Log.e("MedicationActivity", "등록된 보호자 없음");
                    robot.speak(TtsRequest.create("연결할 보호자 연락처가 없습니다.", false));
                }

                finish();
            }
        }.start();

        btnMedicationDone.setOnClickListener(v -> {
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }

            tvMessage.setText("복약이 확인되었습니다.");
            tvTimer.setText("알람 종료");

            // 정상 복약 시 음성 안내 후 복귀
            robot.speak(TtsRequest.create("복약이 확인되었습니다. 홈 베이스로 복귀합니다.", false));

            // [추가] 4. 정상 처리 후 충전소로 복귀 및 트래킹 해제
            robot.setTrackUserOn(false);
            robot.goTo("home base");

            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (robot != null) {
            robot.setTrackUserOn(false); // 안전장치 추가
        }
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}