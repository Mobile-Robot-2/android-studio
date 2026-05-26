package com.temi.rhythmgame;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PrepareActivity extends AppCompatActivity {

    private static final String TAG = "PrepareActivity";

    /** 체크 간격 (ms) — 너무 빠르면 ⏳ 표시가 안 보임 */
    private static final long CHECK_INTERVAL_MS = 1_500L;
    /** 첫 번째 체크 시작 딜레이 (ms) */
    private static final long CHECK_START_DELAY_MS = 800L;

    private TextView tvCountdown;
    private TextView tvCheckLog;

    private CountDownTimer countDownTimer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 화면에 누적되는 체크 결과 로그 */
    private final List<String> logLines = new ArrayList<>();

    // ══════════════════════════════════════════════════════════════════════
    //  점검 항목 정의
    //  ─────────────────────────────────────────────────────────────────────
    //  추후 항목 추가 시 이 메서드에만 add() 한 줄 추가하면 됩니다.
    //  예) checks.add(new CheckItem("아두이노 연결", this::checkArduino));
    //      checks.add(new CheckItem("서버 연결",     this::checkServer));
    //      checks.add(new CheckItem("MediaPipe 모델", this::checkModelFile));
    // ══════════════════════════════════════════════════════════════════════

    private List<CheckItem> buildChecks() {
        List<CheckItem> checks = new ArrayList<>();
        checks.add(new CheckItem("카메라 권한",   this::checkCameraPermission));
        checks.add(new CheckItem("영상 파일",     this::checkVideoFile));
        checks.add(new CheckItem("카메라 초기화", this::warmUpCamera));
        return checks;
    }

    // ═══════════════════════════════ 생명주기 ═════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prepare);

        tvCountdown = findViewById(R.id.tvCountdown);
        tvCheckLog  = findViewById(R.id.tvCheckLog);

        scheduleChecks();
        startCountdown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) countDownTimer.cancel();
        handler.removeCallbacksAndMessages(null); // 모든 대기 중인 체크 취소
    }

    // ═══════════════════════════ 체크 스케줄링 ════════════════════════════

    /**
     * buildChecks() 에 정의된 항목들을 CHECK_INTERVAL_MS 간격으로 순차 실행
     */
    private void scheduleChecks() {
        List<CheckItem> checks = buildChecks();
        long delayMs = CHECK_START_DELAY_MS;
        for (CheckItem check : checks) {
            handler.postDelayed(() -> runCheck(check), delayMs);
            delayMs += CHECK_INTERVAL_MS;
        }
    }

    /**
     * 체크 항목 하나를 실행하고 결과를 화면에 반영
     */
    private void runCheck(CheckItem check) {
        // ⏳ 진행 중 표시
        logLines.add("⏳  " + check.label + " 확인 중...");
        renderLog();

        boolean passed = check.runner.run();

        // 마지막 줄을 결과로 교체
        String result = passed
                ? "✅  " + check.label + " 확인 완료"
                : "❌  " + check.label + " 실패";
        logLines.set(logLines.size() - 1, result);
        renderLog();

        Log.d(TAG, "[체크] " + check.label + " → " + (passed ? "통과" : "실패"));
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : logLines) {
            sb.append(line).append("\n");
        }
        tvCheckLog.setText(sb.toString().trim());
    }

    // ═══════════════════════════ 개별 체크 구현 ═══════════════════════════

    /** 체크 1: 카메라 권한 */
    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 체크 2: 영상 파일 존재 여부 (res/raw/guide_video) */
    private boolean checkVideoFile() {
        try {
            getResources().openRawResourceFd(R.raw.guide_video);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "영상 파일 없음: " + e.getMessage());
            return false;
        }
    }

    /**
     * 체크 3: CameraProvider 워밍업
     * ProcessCameraProvider 는 싱글톤이므로 여기서 미리 getInstance() 를 호출해두면
     * MainActivity 에서 카메라가 즉시 표시됩니다.
     */
    private boolean warmUpCamera() {
        try {
            ProcessCameraProvider.getInstance(this).addListener(
                    () -> Log.d(TAG, "CameraProvider 준비 완료"),
                    ContextCompat.getMainExecutor(this)
            );
            return true;
        } catch (Exception e) {
            Log.e(TAG, "CameraProvider 워밍업 실패: " + e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════ 카운트다운 ═══════════════════════════════

    private void startCountdown() {
        countDownTimer = new CountDownTimer(10_100, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) Math.ceil(millisUntilFinished / 1000.0);
                tvCountdown.setText(String.valueOf(secondsLeft));
            }

            @Override
            public void onFinish() {
                Log.d(TAG, "카운트다운 완료 → MainActivity 전환");
                startActivity(new Intent(PrepareActivity.this, MainActivity.class));
                finish();
            }
        }.start();
    }

    // ═══════════════════════════ CheckItem ════════════════════════════════

    /**
     * 체크 항목 하나를 나타내는 클래스
     * label  : 화면에 표시될 이름
     * runner : 실제 체크 로직 (true = 통과, false = 실패)
     */
    private static class CheckItem {
        final String label;
        final CheckRunner runner;

        @FunctionalInterface
        interface CheckRunner {
            boolean run();
        }

        CheckItem(String label, CheckRunner runner) {
            this.label = label;
            this.runner = runner;
        }
    }
}
