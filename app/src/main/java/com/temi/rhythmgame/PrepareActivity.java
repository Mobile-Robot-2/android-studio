package com.temi.rhythmgame;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class PrepareActivity extends AppCompatActivity {

    private static final String TAG = "PrepareActivity";
    private CountDownTimer countDownTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prepare);

        TextView tvCountdown = findViewById(R.id.tvCountdown);
        TextView tvMessage   = findViewById(R.id.tvMessage);

        // Bug Fix: CountDownTimer는 첫 tick이 (전체시간 - interval)ms 남은 시점에 발생
        // → 10000ms 기준이면 9000ms 남은 시점이 첫 tick → ceil(9.0) = 9 로 "10"을 건너뜀
        // 해결: 10100ms 로 설정하면 첫 tick ≈ 9100ms → ceil(9.1) = 10 부터 시작
        //       마지막 tick ≈ 100ms → ceil(0.1) = 1 로 1까지 정확히 표시됨
        countDownTimer = new CountDownTimer(10_100, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) Math.ceil(millisUntilFinished / 1000.0);
                tvCountdown.setText(String.valueOf(secondsLeft));

                if (secondsLeft > 7) {
                    tvMessage.setText("카메라 연결 확인 중...");
                } else if (secondsLeft > 4) {
                    tvMessage.setText("센서 연결 확인 중...");
                } else {
                    tvMessage.setText("센서 위치를 확인해주세요");
                }
            }

            @Override
            public void onFinish() {
                Log.d(TAG, "카운트다운 완료 → MainActivity 전환");
                startActivity(new Intent(PrepareActivity.this, MainActivity.class));
                finish();
            }
        }.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) countDownTimer.cancel();
    }
}
