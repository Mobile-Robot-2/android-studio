package com.temi.rhythmgame;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

/**
 * ResultActivity - 결과 화면
 *
 * 초기 10초: "결과를 계산하고 있어요!" + ProgressBar (로딩 화면)
 * 10초 후  : 점수 + LLM 피드백 자리 + "다시 하기" 버튼 표시
 */
public class ResultActivity extends AppCompatActivity {

    private static final String TAG = "ResultActivity";
    private CountDownTimer loadingTimer;
    private int finalCalculatedScore = 0;
    private int totalTouchCount = 0;

    private Robot robot; // 로봇 객체 선언

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 화면 꺼짐 방지: 10초 대기 중 화면이 꺼지는 불상사 방지
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_result);
        Log.d(TAG, "ResultActivity 생성됨 - 결과 계산 시작");

        robot = Robot.getInstance(); // 로봇 초기화

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

        // MainActivity에서 전달받은 시간 구간
        String gameStartTime = getIntent().getStringExtra("startTime");
        String gameEndTime = getIntent().getStringExtra("endTime");

        Log.d(TAG, "점수 계산 구간: " + gameStartTime + " ~ " + gameEndTime);

        // Firebase 데이터 비동기 호출 시작
        fetchFirebaseDataAndCalculate(gameStartTime, gameEndTime);

        // 10초 로딩 타이머 시작
        loadingTimer = new CountDownTimer(10000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                Log.d(TAG, "결과 계산 중... " + secondsLeft + "초 남음");
            }

            @Override
            public void onFinish() {
                // 로딩 숨기기
                tvLoading.setVisibility(View.GONE);
                progressBar.setVisibility(View.GONE);

                // UI 업데이트
                tvScore.setText("최종 점수: " + finalCalculatedScore + " (총 터치: " + totalTouchCount + ")");
                tvScore.setVisibility(View.VISIBLE);

                tvFeedback.setText("리듬감이 아주 훌륭합니다!");
                tvFeedback.setVisibility(View.VISIBLE);
                btnRestart.setVisibility(View.VISIBLE);

                // TTS 음성 출력 추가 (점수 동적 바인딩)
                String ttsMessage = "최종 점수는 " + finalCalculatedScore + "점입니다. 정말 수고하셨습니다!";
                robot.speak(TtsRequest.create(ttsMessage, false));

                // 데모 시나리오 일치화: 결과 출력 후 홈 베이스 복귀 (필요 시 주석 해제)
                robot.goTo("홈베이스");
            }
        }.start();

        // "다시 하기" 버튼
        btnRestart.setOnClickListener(v -> {
            Log.d(TAG, "다시 하기 클릭 → StartActivity로 초기화 이동");

            // TTS 강제 종료: 말하고 있는 도중에 버튼을 누르면 말이 겹치지 않도록 끊어줌
            robot.cancelAllTtsRequests();

            Intent intent = new Intent(this, StartActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    // Firebase 쿼리 및 비즈니스 로직
    private void fetchFirebaseDataAndCalculate(String startTime, String endTime) {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("pad_data");

        Log.d(TAG, "요청한 검색 구간: " + startTime + " ~ " + endTime);

        // start_time을 기준으로 게임 시작 시간부터 종료 시간까지의 데이터만 필터링
        Query gameDataQuery = ref.orderByChild("start_time").startAt(startTime).endAt(endTime);

        // addListenerForSingleValueEvent: 지속적인 구독이 아닌 1회성 데이터 읽기 (결과창에 적합)
        gameDataQuery.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int tempScore = 0;
                int count = 0;

                for (DataSnapshot data : snapshot.getChildren()) {
                    // 키 값을 통해 데이터 추출
                    Integer pressure = data.child("pressure").getValue(Integer.class);
                    Long duration = data.child("duration_ms").getValue(Long.class);

                    if (pressure != null && duration != null) {
                        count++;
                        // 예시 비즈니스 로직: 압력이 80 이상이면 10점, 누른 시간이 500ms 이상이면 보너스 등
                        if (pressure > 80) {
                            tempScore += 10;
                        } else {
                            tempScore += 5;
                        }
                    }
                }

                // 전역 변수에 최종 결과 저장 (타이머 종료 시 화면에 반영됨)
                finalCalculatedScore = tempScore;
                totalTouchCount = count;
                Log.d(TAG, "데이터 집계 완료. 총 건수: " + count + ", 합산 점수: " + finalCalculatedScore);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase 데이터 호출 실패: " + error.getMessage());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loadingTimer != null) {
            loadingTimer.cancel();
            Log.d(TAG, "로딩 타이머 취소됨");
        }
        // 안전장치
        if (robot != null) {
            robot.cancelAllTtsRequests();
        }
    }
}
