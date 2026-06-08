package com.temi.rhythmgame;

import android.util.Log;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

// import com.robotemi.sdk.Robot;
// import com.robotemi.sdk.TtsRequest;

public class MedicationActivity extends AppCompatActivity {

    // private Robot robot;

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

        // robot = Robot.getInstance();

        tvMessage = findViewById(R.id.tvMessage);
        tvTimer = findViewById(R.id.tvTimer);
        btnMedicationDone = findViewById(R.id.btnMedicationDone);

        // 화면 안내 문구
        tvMessage.setText(
                "복약하실 시간입니다.\n약을 드셨다면 복약 완료 버튼을 눌러주세요."
        );

        // Temi 실기기 테스트 시 주석 해제
        /*
        robot.speak(
                TtsRequest.create(
                        "복약하실 시간입니다. 약을 드셨다면 복약 완료 버튼을 눌러주세요.",
                        false
                )
        );
        */

        countDownTimer = new CountDownTimer(
                60000,
                1000
        ) {

            @Override
            public void onTick(long millisUntilFinished) {

                int secondsLeft =
                        (int) (millisUntilFinished / 1000);

                tvTimer.setText(
                        secondsLeft +
                                "초 후 보호자에게 연락합니다."
                );
            }

            @Override
            public void onFinish() {

                Log.d(
                        "MedicationActivity",
                        "60초 경과 - 보호자 영상통화 실행 예정"
                );

                tvMessage.setText(
                        "응답이 없어 보호자에게 연락합니다."
                );

                tvTimer.setText(
                        "영상통화 연결 예정"
                );

                // Temi 실기기 테스트 시 주석 해제
                /*
                robot.speak(
                        TtsRequest.create(
                                "응답이 없어 보호자에게 연락합니다.",
                                false
                        )
                );
                */

                // 실제 peerId 확보 후 사용
                /*
                robot.startTelepresence(
                        "보호자",
                        "peerId"
                );
                */

                finish();
            }
        }.start();

        btnMedicationDone.setOnClickListener(v -> {

            if (countDownTimer != null) {
                countDownTimer.cancel();
            }

            tvMessage.setText(
                    "복약이 확인되었습니다."
            );

            tvTimer.setText(
                    "알람 종료"
            );

            // Temi 실기기 테스트 시 주석 해제
            /*
            robot.speak(
                    TtsRequest.create(
                            "복약이 확인되었습니다.",
                            false
                    )
            );
            */

            finish();
        });
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}