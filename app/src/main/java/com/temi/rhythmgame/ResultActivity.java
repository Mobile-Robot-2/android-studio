package com.temi.rhythmgame;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * ResultActivity - 결과 화면
 *
 * 초기 10초: "결과를 계산하고 있어요!" + ProgressBar (로딩 화면)
 * 10초 후  : 점수 + LLM 피드백 자리 + "다시 하기" 버튼 표시
 */
public class ResultActivity extends AppCompatActivity {

    private static final String TAG = "ResultActivity";
    private CountDownTimer loadingTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        Log.d(TAG, "ResultActivity 생성됨 - 결과 계산 시작");

        // 뷰 참조
        TextView  tvLoading   = findViewById(R.id.tvLoading);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        TextView  tvScore     = findViewById(R.id.tvScore);
        TextView  tvFeedback  = findViewById(R.id.tvFeedback);
        Button    btnRestart  = findViewById(R.id.btnRestart);

        // 초기 상태: 결과 숨기고 로딩 화면만 표시
        tvScore.setVisibility(View.GONE);
        tvFeedback.setVisibility(View.GONE);
        btnRestart.setVisibility(View.GONE);

        // MainActivity에서 전달받은 점수 데이터
        int score    = getIntent().getIntExtra("score", 0);
        int maxScore = getIntent().getIntExtra("maxScore", 10);
        Log.d(TAG, "받은 점수: " + score + "/" + maxScore);

        // 10초 로딩 후 결과 표시
        loadingTimer = new CountDownTimer(10000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                Log.d(TAG, "결과 계산 중... " + secondsLeft + "초 남음");
            }

            @Override
            public void onFinish() {
                Log.d(TAG, "로딩 완료 - 결과 표시");

                // 로딩 화면 숨기기
                tvLoading.setVisibility(View.GONE);
                progressBar.setVisibility(View.GONE);

                // 결과 화면 표시
                tvScore.setText("최종 점수: " + score + "/" + maxScore);
                tvScore.setVisibility(View.VISIBLE);
                tvFeedback.setVisibility(View.VISIBLE);
                btnRestart.setVisibility(View.VISIBLE);
            }
        }.start();

        // "다시 하기" 버튼: StartActivity로 이동 (백스택 전체 초기화)
        btnRestart.setOnClickListener(v -> {
            Log.d(TAG, "다시 하기 클릭 → StartActivity로 초기화 이동");
            Intent intent = new Intent(this, StartActivity.class);
            // FLAG_ACTIVITY_CLEAR_TOP: 기존 스택의 StartActivity 위 Activity 모두 제거
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 메모리 누수 방지: Activity 종료 시 타이머 취소
        if (loadingTimer != null) {
            loadingTimer.cancel();
            Log.d(TAG, "로딩 타이머 취소됨");
        }
    }
}
