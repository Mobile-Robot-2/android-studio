package com.temi.rhythmgame;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.robotemi.sdk.BatteryData;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnBatteryStatusChangedListener;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.robotemi.sdk.UserInfo;

public class MainActivity extends AppCompatActivity implements OnBatteryStatusChangedListener {

    private static final String TAG = "MainActivity";
    private static final String SERVER_URL = "http://10.168.141.21:8000";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private FillVideoView videoView;
    private TextView textStatus;
    private PreviewView previewView;
    private String gameStartTimeStr;
    private String gameEndTimeStr;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA);

    private Robot robot;

    private int currentVideoType = 1;
    private ExecutorService cameraExecutor;
    private boolean mirrorOverlay = true;
    private final OkHttpClient client = new OkHttpClient();

    private long lastSendTime = 0;

    private boolean emergencyTriggered = false;
    private boolean fallMode = false;
    private boolean isCalledLaunched = false;

    // ───────────────── 생명주기 ──────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));

        hideSystemUI();
        setContentView(R.layout.activity_main);

        fallMode = getIntent().getBooleanExtra("fall_mode", false);
        Log.d("FALL_MODE", "fallMode = " + fallMode);

        boolean reset =
                getIntent().getBooleanExtra("RESET_GAME", false);

        if (reset) {
            resetGameState();
        }

        videoView = findViewById(R.id.videoView);
        previewView = findViewById(R.id.previewView);
        cameraExecutor = Executors.newSingleThreadExecutor();

        textStatus = findViewById(R.id.textStatus);
        textStatus.setVisibility(View.GONE);

        if (!fallMode) {
            setupVideo();
        }

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        robot = Robot.getInstance();
        robot.tiltAngle(-15);
        robot.setTrackUserOn(false);
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
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
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
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = getAvailableCameraSelector(cameraProvider);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );
            } catch (ExecutionException | InterruptedException e) {
                textStatus.setText(getString(R.string.pose_failed, e.getMessage()));
            } catch (CameraInfoUnavailableException | IllegalArgumentException e) {
                textStatus.setText(getString(R.string.pose_failed, "사용 가능한 카메라를 찾을 수 없습니다."));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private CameraSelector getAvailableCameraSelector(ProcessCameraProvider cameraProvider)
            throws CameraInfoUnavailableException {
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            mirrorOverlay = true;
            return CameraSelector.DEFAULT_FRONT_CAMERA;
        }

        mirrorOverlay = false;
        return CameraSelector.DEFAULT_BACK_CAMERA;
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();

        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        if (System.currentTimeMillis() - lastSendTime < 500) {
            imageProxy.close();
            return;
        }

        lastSendTime = System.currentTimeMillis();

        try {
            byte[] nv21 = imageProxyToNV21(imageProxy);

            YuvImage yuvImage = new YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    imageProxy.getWidth(),
                    imageProxy.getHeight(),
                    null
            );

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            yuvImage.compressToJpeg(
                    new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()),
                    60,
                    out
            );

            byte[] jpegBytes = out.toByteArray();
            sendImageToServer(jpegBytes);

        } catch (Exception e) {
            runOnUiThread(() ->
                    textStatus.setText("Image convert failed\n" + e.getMessage())
            );
        } finally {
            imageProxy.close();
        }
    }

    private void sendImageToServer(byte[] jpegBytes) {
        RequestBody imageBody = RequestBody.create(
                MediaType.parse("image/jpeg"),
                jpegBytes
        );

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "frame.jpg", imageBody)
                .build();

        Request request = new Request.Builder()
                .url(SERVER_URL + "/analyze")
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("SERVER_ERROR", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String result = response.body().string();
                Log.d("SERVER_RESPONSE", result);

                try {
                    JSONObject json = new JSONObject(result);
                    boolean fallDetected = json.optBoolean("fall_detected", false);

                    Log.d(
                            "CHECK",
                            "fallMode=" + fallMode +
                                    ", fallDetected=" + fallDetected +
                                    ", emergencyTriggered=" + emergencyTriggered
                    );

                    if (fallMode && fallDetected && !emergencyTriggered) {
                        emergencyTriggered = true;
                        Log.d("FALL_DETECTED", "낙상 감지1");

                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "낙상 감지!", Toast.LENGTH_SHORT).show();
                            callGuardian();
                        });
                    }
                } catch (Exception e) {
                    Log.e("JSON_ERROR", e.getMessage());
                }
            }
        });
    }

    private void resetGameState() {
        RequestBody body = RequestBody.create(
                MediaType.parse("text/plain"),
                ""
        );

        Request request = new Request.Builder()
                .url(SERVER_URL + "/reset")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() ->
                        textStatus.setText("게임 시작 실패\n" + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                runOnUiThread(() -> textStatus.setText("게임 시작!"));
            }
        });
    }

    private byte[] imageProxyToNV21(ImageProxy image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);
        return nv21;
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

    private void callGuardian() {

        robot.speak(TtsRequest.create("낙상이 감지되어 보호자에게 긴급 연락을 시도합니다.", false));
        Robot robot = Robot.getInstance();

        UserInfo adminInfo = robot.getAdminInfo();

        if (adminInfo != null) {
            String targetName = adminInfo.getName();
            String targetUserId = adminInfo.getUserId();

            isCalledLaunched = true;

            robot.startTelepresence(targetName, targetUserId, com.robotemi.sdk.constants.Platform.MOBILE);


        } else {

            Log.e(
                    "CALL",
                    "보호자 정보 없음"
            );
        }


    }
}

