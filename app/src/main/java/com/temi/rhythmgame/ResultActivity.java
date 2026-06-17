package com.temi.rhythmgame;

import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
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
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ResultActivity - 결과 화면
 *
 * 초기 10초: "결과를 계산하고 있어요!" + ProgressBar (로딩 화면)
 * 10초 후  : 점수 + LLM 피드백 자리 + "다시 하기" 버튼 표시
 */
public class ResultActivity extends AppCompatActivity {

    private static final String TAG = "ResultActivity";
    private CountDownTimer loadingTimer;

    private int finalCalculatedScore = 0; // 100점 만점 기준 점수
    private int totalTouchCount = 0;      // 단순 센서 터치 횟수
    private int hitCount = 0;             // 정답 구간 내 터치 성공 횟수

    private Robot robot;

    // ⭐️ 영상 1 정답 구간 (단위: 초)
    private final double[][] LEVEL_1_WINDOWS = {
            {2.0, 8.0},   // 왼손 드세요
            {8.0, 13.0},  // 오른손 드세요
            {13.0, 19.0}, // 버튼을 누르세요
            {19.0, 24.0}, // 왼손을 드세요
            {24.0, 27.0}, // 왼손을 드세요
            {27.0, 33.0}  // 오른손 드세요
    };

    // ⭐️ 영상 2 정답 구간 (단위: 초)
    private final double[][] LEVEL_2_WINDOWS = {
            {3.0, 9.0},   // 박수 짝짝
            {9.0, 14.0},  // 왼손 들기
            {14.0, 19.0}, // 오른손 들기
            {19.0, 24.0}, // 버튼을 누르세요
            {24.0, 29.0}, // 박수 짝짝
            {29.0, 33.0}  // 오른손 들기
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_result);

        // 배경 애니메이션 실행
        ConstraintLayout rootLayout = findViewById(R.id.rootLayout);
        if (rootLayout != null && rootLayout.getBackground() instanceof AnimationDrawable) {
            AnimationDrawable animationDrawable = (AnimationDrawable) rootLayout.getBackground();
            animationDrawable.setEnterFadeDuration(2000);
            animationDrawable.setExitFadeDuration(4000);
            animationDrawable.start();
        }

        robot = Robot.getInstance();

        TextView tvLoading = findViewById(R.id.tvLoading);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        TextView tvScore = findViewById(R.id.tvScore);
        TextView tvFeedback = findViewById(R.id.tvFeedback);
        Button btnRestart = findViewById(R.id.btnRestart);

        tvScore.setVisibility(View.GONE);
        tvFeedback.setVisibility(View.GONE);
        btnRestart.setVisibility(View.GONE);

        // MainActivity로부터 값 수신 (gameLevel 추가)
        String gameStartTime = getIntent().getStringExtra("startTime");
        String gameEndTime = getIntent().getStringExtra("endTime");
        int gameLevel = getIntent().getIntExtra("gameLevel", 1); // 기본값 1

        Log.d(TAG, "게임 레벨: " + gameLevel + " / 점수 계산 구간: " + gameStartTime + " ~ " + gameEndTime);

        // Firebase 데이터 호출 및 점수 계산
        fetchFirebaseDataAndCalculate(gameStartTime, gameEndTime, gameLevel);

        // 10초 로딩 타이머
        loadingTimer = new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                Log.d(TAG, "결과 계산 중... " + (millisUntilFinished / 1000) + "초 남음");
            }

            @Override
            public void onFinish() {
                tvLoading.setVisibility(View.GONE);
                progressBar.setVisibility(View.GONE);

                // UI 업데이트 (성공 횟수에 비례한 100점 만점 점수)
                tvScore.setText("최종 점수: " + finalCalculatedScore + "점\n(정답: " + hitCount + "/6)");
                tvScore.setVisibility(View.VISIBLE);

                // 피드백 문구 동적 생성
                String feedbackMsg = finalCalculatedScore >= 80 ? "리듬감이 아주 훌륭합니다! 완벽해요!" :
                        finalCalculatedScore >= 50 ? "좋아요! 조금만 더 연습하면 완벽해질 거예요." :
                                "끝까지 포기하지 않은 모습이 멋집니다!";
                tvFeedback.setText(feedbackMsg);
                tvFeedback.setVisibility(View.VISIBLE);
                btnRestart.setVisibility(View.VISIBLE);

                String ttsMessage = "최종 점수는 " + finalCalculatedScore + "점입니다. 수고하셨습니다!";
                robot.speak(TtsRequest.create(ttsMessage, false));

                robot.goTo("home base");
            }
        }.start();

        btnRestart.setOnClickListener(v -> {
            robot.cancelAllTtsRequests();
            Intent intent = new Intent(this, StartActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void fetchFirebaseDataAndCalculate(String startTimeStr, String endTimeStr, int gameLevel) {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("pad_data");
        Query gameDataQuery = ref.orderByChild("start_time").startAt(startTimeStr).endAt(endTimeStr);

        gameDataQuery.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                long gameStartMs = parseTimeToMillis(startTimeStr);

                // 선택된 영상에 맞는 정답 구간 배열 할당
                double[][] currentWindows = (gameLevel == 1) ? LEVEL_1_WINDOWS : LEVEL_2_WINDOWS;
                int totalWindows = currentWindows.length;

                // 중복 득점 방지 (한 구간에서 여러 번 눌러도 1번만 인정)
                boolean[] isWindowHit = new boolean[totalWindows];

                for (DataSnapshot data : snapshot.getChildren()) {
                    String touchTimeStr = data.child("start_time").getValue(String.class);
                    Integer pressure = data.child("pressure").getValue(Integer.class);

                    if (touchTimeStr != null && pressure != null) {
                        totalTouchCount++;

                        // 압력이 너무 낮은(오터치) 경우는 무시할 수 있도록 임계값 설정 (필요시 조정)
                        if (pressure < 20) continue;

                        long touchTimeMs = parseTimeToMillis(touchTimeStr);
                        double relativeSeconds = (touchTimeMs - gameStartMs) / 1000.0;

                        // TODO: 추후 MediaPipe 연동 시 Left/Right/Clap 조건을 이 반복문 내에 추가
                        for (int i = 0; i < totalWindows; i++) {
                            // 아직 해당 구간에서 득점하지 않았고, 터치 시간이 구간 내에 존재한다면
                            if (!isWindowHit[i] && relativeSeconds >= currentWindows[i][0] && relativeSeconds <= currentWindows[i][1]) {
                                isWindowHit[i] = true;
                                hitCount++;
                                break; // 하나의 터치는 하나의 정답 구간에만 매핑
                            }
                        }
                    }
                }

                // 100점 만점으로 환산 (맞춘 개수 / 전체 정답 수 * 100)
                finalCalculatedScore = (int) (((double) hitCount / totalWindows) * 100);

                Log.d(TAG, "총 센서 반응: " + totalTouchCount + ", 정답 인정: " + hitCount + "/" + totalWindows);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase 데이터 호출 실패: " + error.getMessage());
            }
        });
    }

    /**
     * Firebase에 저장된 start_time 형식이 timestamp(Long)인지 문자열(yyyy-MM-dd)인지
     * 유연하게 대처하여 밀리초 단위로 변환해주는 헬퍼 메서드입니다.
     */
    private long parseTimeToMillis(String timeStr) {
        if (timeStr == null) return 0;
        try {
            // 1. Unix epoch (예: "1718000000000") 로 저장된 경우
            return Long.parseLong(timeStr);
        } catch (NumberFormatException e) {
            // 2. 날짜 포맷 (예: "2026-06-17 11:22:29") 으로 저장된 경우
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                Date date = sdf.parse(timeStr);
                return date != null ? date.getTime() : 0;
            } catch (Exception ex) {
                Log.e(TAG, "시간 파싱 오류: " + timeStr);
                return 0;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loadingTimer != null) {
            loadingTimer.cancel();
        }
        if (robot != null) {
            robot.cancelAllTtsRequests();
        }
    }
}
