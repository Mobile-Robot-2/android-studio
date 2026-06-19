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

    private int finalCalculatedScore = 0;
    private int hitCount = 0;

    private Robot robot;

    // ⭐️ 요구 동작 상수 정의 (MediaPipe JSON 키값 대응)
    private static final int ACTION_LEFT = 1;   // LEFT_HAND_UP
    private static final int ACTION_RIGHT = 2;  // RIGHT_HAND_UP
    private static final int ACTION_CLAP = 3;   // CLAP
    private static final int ACTION_BUTTON = 4; // 버튼 터치 (임시: 비전 감지 아무 동작이나 허용)

    // ⭐️ 영상 1 정답 구간 (단위: 초) 및 요구 동작
    private final double[][] LEVEL_1_WINDOWS = {
            {2.0, 8.0},   // 1. 왼손 드세요
            {8.0, 13.0},  // 2. 오른손 드세요
            {13.0, 19.0}, // 3. 버튼을 누르세요
            {19.0, 24.0}, // 4. 왼손을 드세요
            {24.0, 27.0}, // 5. 왼손을 드세요
            {27.0, 33.0}  // 6. 오른손 드세요
    };
    private final int[] LEVEL_1_ACTIONS = {
            ACTION_LEFT, ACTION_RIGHT, ACTION_BUTTON, ACTION_LEFT, ACTION_LEFT, ACTION_RIGHT
    };

    // ⭐️ 영상 2 정답 구간 (단위: 초) 및 요구 동작
    private final double[][] LEVEL_2_WINDOWS = {
            {3.0, 9.0},   // 1. 박수 짝짝
            {9.0, 14.0},  // 2. 왼손 들기
            {14.0, 19.0}, // 3. 오른손 들기
            {19.0, 24.0}, // 4. 버튼을 누르세요
            {24.0, 29.0}, // 5. 박수 짝짝
            {29.0, 33.0}  // 6. 오른손 들기
    };
    private final int[] LEVEL_2_ACTIONS = {
            ACTION_CLAP, ACTION_LEFT, ACTION_RIGHT, ACTION_BUTTON, ACTION_CLAP, ACTION_RIGHT
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_result);

        // 배경 애니메이션
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

        // MainActivity로부터 값 수신
        String gameStartTime = getIntent().getStringExtra("startTime");
        String gameEndTime = getIntent().getStringExtra("endTime");
        int gameLevel = getIntent().getIntExtra("gameLevel", 1);

        Log.d(TAG, "게임 레벨: " + gameLevel + " / 영상 시작 시간: " + gameStartTime);

        // ⭐️ Firebase 데이터 호출 및 MediaPipe 기반 점수 계산
        fetchFirebaseDataAndCalculate(gameStartTime, gameLevel);

        loadingTimer = new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                Log.d(TAG, "결과 계산 중... " + (millisUntilFinished / 1000) + "초 남음");
            }

            @Override
            public void onFinish() {
                tvLoading.setVisibility(View.GONE);
                progressBar.setVisibility(View.GONE);

                tvScore.setText("최종 점수: " + finalCalculatedScore + "점\n(정답: " + hitCount + "/6)");
                tvScore.setVisibility(View.VISIBLE);

                String feedbackMsg = finalCalculatedScore >= 80 ? "리듬감이 아주 훌륭합니다! 완벽해요!" :
                        finalCalculatedScore >= 50 ? "좋아요! 조금만 더 연습하면 완벽해질 거예요." :
                                "끝까지 포기하지 않은 모습이 멋집니다!";
                tvFeedback.setText(feedbackMsg);
                tvFeedback.setVisibility(View.VISIBLE);
                btnRestart.setVisibility(View.VISIBLE);

                String ttsMessage = "최종 점수는 " + finalCalculatedScore + "점입니다. 수고하셨습니다!";
                robot.speak(TtsRequest.create(ttsMessage, false));

                // robot.goTo("home base");
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

    private void fetchFirebaseDataAndCalculate(String startTimeStr, int gameLevel) {
        // "게임 시작 시 DB 삭제" 방식을 따르므로 전체 history를 순회합니다.
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("game/history");

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // 1. 영상이 켜진 절대 시간을 밀리초로 변환 (PrepareActivity의 10초 대기가 이미 포함된 시점)
                long gameStartMs = parseTimeToMillis(startTimeStr);

                double[][] currentWindows = (gameLevel == 1) ? LEVEL_1_WINDOWS : LEVEL_2_WINDOWS;
                int[] currentActions = (gameLevel == 1) ? LEVEL_1_ACTIONS : LEVEL_2_ACTIONS;
                int totalWindows = currentWindows.length;
                boolean[] isWindowHit = new boolean[totalWindows];

                // ⭐️ 이전 프레임의 카운트를 기억할 변수
                int prevClap = 0, prevLeft = 0, prevRight = 0, prevArms = 0;

                for (DataSnapshot data : snapshot.getChildren()) {
                    Double pythonTimestamp = data.child("timestamp").getValue(Double.class);

                    // JSON 내 counts 추출 (null 방어)
                    DataSnapshot counts = data.child("counts");
                    Integer clap = counts.child("CLAP").getValue(Integer.class);
                    Integer left = counts.child("LEFT_HAND_UP").getValue(Integer.class);
                    Integer right = counts.child("RIGHT_HAND_UP").getValue(Integer.class);
                    Integer arms = counts.child("ARMS_OPEN").getValue(Integer.class);

                    int currClap = (clap != null) ? clap : prevClap;
                    int currLeft = (left != null) ? left : prevLeft;
                    int currRight = (right != null) ? right : prevRight;
                    int currArms = (arms != null) ? arms : prevArms;

                    // 2. 카운트가 이전보다 '증가'했다면 그 순간 동작을 한 것으로 판별
                    boolean didClap = currClap > prevClap;
                    boolean didLeft = currLeft > prevLeft;
                    boolean didRight = currRight > prevRight;
                    boolean didArms = currArms > prevArms;
                    boolean didAny = didClap || didLeft || didRight || didArms; // 버튼 터치 대용

                    // 값 갱신
                    prevClap = currClap; prevLeft = currLeft; prevRight = currRight; prevArms = currArms;

                    if (pythonTimestamp != null && didAny) { // 어떤 동작이든 했을 때만 시간 검사
                        // 3. 파이썬 절대 시간(초)을 자바 밀리초로 변환
                        long touchTimeMs = (long) (pythonTimestamp * 1000);

                        // 4. 영상 시작 시간과 빼서 완벽하게 보정된 상대 시간(초) 계산
                        double relativeSeconds = (touchTimeMs - gameStartMs) / 1000.0;

                        // 10초 대기 시간(마이너스 초)에 수행한 동작은 채점에서 무시
                        if (relativeSeconds < 0) continue;

                        for (int i = 0; i < totalWindows; i++) {
                            // 아직 득점하지 않은 구간이고, 수행 시간이 정답 구간 내에 있다면
                            if (!isWindowHit[i] && relativeSeconds >= currentWindows[i][0] && relativeSeconds <= currentWindows[i][1]) {

                                int reqAction = currentActions[i];
                                boolean isHit = false;

                                // 5. 해당 구간이 요구하는 동작과 일치하는지 검증
                                if (reqAction == ACTION_LEFT && didLeft) isHit = true;
                                else if (reqAction == ACTION_RIGHT && didRight) isHit = true;
                                else if (reqAction == ACTION_CLAP && didClap) isHit = true;
                                else if (reqAction == ACTION_BUTTON && didAny) isHit = true; // '버튼' 구간은 MediaPipe 임의 동작으로 대체 인정

                                if (isHit) {
                                    isWindowHit[i] = true;
                                    hitCount++;
                                    break;
                                }
                            }
                        }
                    }
                }

                finalCalculatedScore = (int) (((double) hitCount / totalWindows) * 100);
                Log.d(TAG, "비전 데이터 분석 완료 - 정답 인정: " + hitCount + "/" + totalWindows);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase 데이터 호출 실패: " + error.getMessage());
            }
        });
    }

    private long parseTimeToMillis(String timeStr) {
        if (timeStr == null) return 0;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date date = sdf.parse(timeStr);
            return date != null ? date.getTime() : 0;
        } catch (Exception ex) {
            Log.e(TAG, "시간 파싱 오류: " + timeStr);
            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loadingTimer != null) loadingTimer.cancel();
        if (robot != null) robot.cancelAllTtsRequests();
    }
}
