package com.temi.rhythmgame;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int GAME_DURATION_MS = 60_000;

    private FillVideoView videoView;
    private PreviewView previewView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable gameEndRunnable;

    // ───────────────── 생명주기 ──────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemUI();
        setContentView(R.layout.activity_main);

        videoView   = findViewById(R.id.videoView);
        previewView = findViewById(R.id.previewView);

        setupVideo();
        setupGameTimer();

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
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
        if (gameEndRunnable != null) handler.removeCallbacks(gameEndRunnable);
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
        Uri videoUri = Uri.parse(
                "android.resource://" + getPackageName() + "/" + R.raw.guide_video);
        videoView.setVideoURI(videoUri);
        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            videoView.start();
            Log.d(TAG, "영상 재생 시작");
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "영상 오류 what=" + what + " extra=" + extra);
            return true;
        });
    }

    // ───────────────── 게임 타이머 ───────────────────────────────────────

    private void setupGameTimer() {
        gameEndRunnable = () -> {
            if (videoView != null && videoView.isPlaying()) videoView.stopPlayback();
            Intent intent = new Intent(MainActivity.this, ResultActivity.class);
            intent.putExtra("score", 0);
            intent.putExtra("maxScore", 10);
            startActivity(intent);
            finish();
        };
        handler.postDelayed(gameEndRunnable, GAME_DURATION_MS);
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
