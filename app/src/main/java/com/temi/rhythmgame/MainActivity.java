package com.temi.rhythmgame;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.robotemi.sdk.BatteryData;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.listeners.OnBatteryStatusChangedListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity implements OnBatteryStatusChangedListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private FillVideoView videoView;
    private PreviewView previewView;
    private String gameStartTimeStr;
    private String gameEndTimeStr;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA);

    private Robot robot;

    private int currentVideoType = 1;

    // ───────────────── 생명주기 ──────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));

        hideSystemUI();
        setContentView(R.layout.activity_main);

        videoView   = findViewById(R.id.videoView);
        previewView = findViewById(R.id.previewView);

        setupVideo();

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        robot = Robot.getInstance();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (robot != null) {
            robot.addOnBatteryStatusChangedListener(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (robot != null) {
            robot.removeOnBatteryStatusChangedListener(this);
        }
    }

    @Override
    public void onBatteryStatusChanged(BatteryData batteryData) {
        int batteryLevel = batteryData.getBatteryPercentage();
        boolean isCharging = batteryData.isCharging();

        // 예시: 20% 이하로 떨어졌고 충전 중이 아닐 때
        if (batteryLevel <= 39 && !isCharging) {

            // 배터리 복귀 화면으로 전환
            Intent intent = new Intent(this, BatteryReturnActivity.class);
            startActivity(intent);

            // 현재 리듬게임 화면 종료
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoView != null && videoView.isPlaying()) videoView.stopPlayback();
    }

    // ───────────────── 전체화면 ──────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    // ───────────────── 영상 ──────────────────────────────────────────────

    private void setupVideo() {
        // ⭐️ 1. PrepareActivity로부터 전달받은 비디오 타입 번호 꺼내기 (기본값은 1)
        int videoType = getIntent().getIntExtra("videoType", 1);
        this.currentVideoType = videoType;
        Log.d(TAG, "선택된 비디오 타입: " + videoType);

        // ⭐️ 2. 번호에 따라 재생할 raw 파일 동적으로 선택
        int videoResId;
        if (videoType == 2) {
            videoResId = R.raw.answer_video_2; // 2번 영상 파일명 (res/raw 폴더에 있어야 함)
        } else {
            videoResId = R.raw.answer_video_1; // 1번 영상 파일명
        }

        Uri videoUri = Uri.parse("android.resource://" + getPackageName() + "/" + videoResId);
        videoView.setVideoURI(videoUri);

        videoView.setOnPreparedListener(mp -> {
            videoView.start();
            gameStartTimeStr = sdf.format(new Date());
            Log.d(TAG, "영상 재생 시작 시간: " + gameStartTimeStr);
        });

        videoView.setOnCompletionListener(mp -> {
            gameEndTimeStr = sdf.format(new Date());
            Log.d(TAG, "영상 종료 시간: " + gameEndTimeStr);
            goToResult();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "영상 오류 what=" + what + " extra=" + extra);
            return true;
        });
    }

    private void goToResult() {
        Intent intent = new Intent(MainActivity.this, ResultActivity.class);
        intent.putExtra("startTime", gameStartTimeStr);
        intent.putExtra("endTime", gameEndTimeStr);
        intent.putExtra("gameLevel", currentVideoType);
        startActivity(intent);
        finish();
    }

    // ───────────────── 포즈 분석 ─────────────────────────────────────────

    public void startPoseAnalysis() {
        Intent intent = new Intent(this, PoseAnalysisActivity.class);
        startActivity(intent);
    }

    // ───────────────── 카메라 ────────────────────────────────────────────

    private void startCamera() {
        // Bug Fix: getInstance()를 한 번만 호출하고 future 객체를 재사용
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                boolean hasFront = false;
                try {
                    hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
                } catch (CameraInfoUnavailableException e) {
                    Log.w(TAG, "전면 카메라 확인 실패, 후면으로 전환");
                }

                CameraSelector selector = hasFront
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview);
                Log.d(TAG, "카메라 시작 (" + (hasFront ? "전면" : "후면") + ")");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "카메라 초기화 실패", e);
                Toast.makeText(this, "카메라를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ───────────────── 카메라 권한 ───────────────────────────────────────

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
                this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
        }
    }
}
