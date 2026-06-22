package com.temi.rhythmgame;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
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
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.UserInfo;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * PatrolActivity - 순찰 동작
 *
 * 시퀀스: 거실 이동 → 고개 최저(-25˚) → 10초 낙상 감시
 *        → 주방 이동 → 고개 최저 → 10초 낙상 감시
 *        → 이상 없으면 home base 복귀.
 *
 * 어느 지점에서든 낙상이 감지되면 그 자리에서 보호자에게 영상통화를 걸고,
 * 통화가 끝나면(onResume) home base 로 복귀하며 이번 순찰을 종료한다.
 */
public class PatrolActivity extends BaseActivity
        implements OnGoToLocationStatusChangedListener {

    private static final String TAG = "PatrolActivity";
    private static final String SERVER_URL = ServerConfig.BASE_URL;
    private static final int REQUEST_CAMERA_PERMISSION = 101;

    // 순찰 지점 (테미 맵에 저장된 이름과 정확히 일치해야 함)
    private static final String[] PATROL_LOCATIONS = {"거실", "주방"};
    private static final String HOME_BASE = "home base";

    private static final long OBSERVE_MS = 10_000;     // 지점별 낙상 관찰 시간
    private static final long HEAD_SETTLE_MS = 1_500;  // 고개가 내려갈 최소 시간
    private static final int SEND_INTERVAL_MS = 500;   // 프레임 전송 간격
    private static final int HEAD_DOWN_ANGLE = -25;    // 고개 최저 (바닥 감지용)
    private static final int HEAD_RESET_ANGLE = 10;    // 복귀 시 기본 시선
    private static final long RETURN_TIMEOUT_MS = 90_000; // 홈베이스 복귀 도착 대기 한도

    private Robot robot;
    private PreviewView previewView;
    private TextView textStatus;
    private RobotStatusHeartbeat statusHeartbeat;
    private ExecutorService cameraExecutor;
    private final OkHttpClient client = new OkHttpClient();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int locationIndex = 0;
    private String currentTarget = null;

    private volatile boolean observing = false;
    private final AtomicBoolean emergencyTriggered = new AtomicBoolean(false);
    private boolean isCallLaunched = false;
    private long lastSendTime = 0;

    // 홈베이스 복귀 단계 추적: 복귀를 "시작"한 순간이 아니라 "도착"한 순간 순찰을 끝낸다.
    private boolean returningToBase = false;
    private boolean finishCalled = false;

    private final Runnable startObservationRunnable = this::startObservation;
    private final Runnable endObservationRunnable = this::endObservation;
    // 도착 콜백이 끝내 오지 않는 경우(도킹 실패 등) 대비한 안전 종료
    private final Runnable returnTimeoutRunnable = () -> {
        Log.w(TAG, "홈베이스 복귀 타임아웃 - 순찰 강제 종료");
        safeFinish();
    };

    // ───────────────── 생명주기 ──────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        // 진행 플래그 보강 (startPatrol 에서 이미 올렸지만, 재생성 등 어떤 경로로 진입해도 보장)
        CareTaskCoordinator.setPatrolInProgress(true);

        hideSystemUI();
        setContentView(R.layout.activity_patrol);

        previewView = findViewById(R.id.previewView);
        textStatus = findViewById(R.id.textStatus);
        statusHeartbeat = new RobotStatusHeartbeat("PATROLLING", "patrol");
        statusHeartbeat.start();
        cameraExecutor = Executors.newSingleThreadExecutor();

        robot = Robot.getInstance();
        robot.addOnGoToLocationStatusChangedListener(this);

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        robot.speak(TtsRequest.create("순찰을 시작합니다.", false));
        startNextLeg();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 낙상으로 영상통화를 걸었다가 통화가 끝나고 돌아온 경우
        if (isCallLaunched) {
            Log.d(TAG, "보호자 통화 종료 확인 - 홈베이스 복귀");
            isCallLaunched = false;
            returnToBaseAndFinish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 순찰 종료 → 진행 플래그 해제 (다음 주기 알람이 정상적으로 순찰을 띄울 수 있게)
        CareTaskCoordinator.setPatrolInProgress(false);
        // 순찰 도중 들어와 대기 중이던 복약/게임 작업이 있으면 이제 하나 실행
        CareTaskCoordinator.runNextPendingTask(getApplicationContext());
        handler.removeCallbacks(startObservationRunnable);
        handler.removeCallbacks(endObservationRunnable);
        handler.removeCallbacks(returnTimeoutRunnable);
        if (robot != null) {
            robot.removeOnGoToLocationStatusChangedListener(this);
            robot.setTrackUserOn(false);
        }
        if (statusHeartbeat != null) {
            statusHeartbeat.stop();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    // ───────────────── 순찰 시퀀스 ───────────────────────────────────────

    /** 다음 순찰 지점으로 이동. 더 이상 지점이 없으면 홈베이스 복귀. */
    private void startNextLeg() {
        observing = false;
        handler.removeCallbacks(endObservationRunnable);

        if (locationIndex >= PATROL_LOCATIONS.length) {
            finishPatrol();
            return;
        }

        currentTarget = PATROL_LOCATIONS[locationIndex];
        updatePatrolStatus("PATROL_MOVING", currentTarget);
        setStatus(currentTarget + "(으)로 이동 중...");
        Log.d(TAG, "이동 시작: " + currentTarget);

        robot.setTrackUserOn(false);
        robot.goTo(currentTarget);
    }

    @Override
    public void onGoToLocationStatusChanged(@NonNull String location,
                                            @NonNull String status,
                                            int descriptionId,
                                            @NonNull String description) {
        // 홈베이스 복귀 단계: 도착(또는 실패)해야 비로소 순찰을 끝낸다.
        if (returningToBase) {
            if (!HOME_BASE.equals(location)) {
                return;
            }
            if (OnGoToLocationStatusChangedListener.COMPLETE.equals(status)) {
                Log.d(TAG, "홈베이스 도착 - 순찰 종료");
                safeFinish();
            } else if (OnGoToLocationStatusChangedListener.ABORT.equals(status)) {
                Log.w(TAG, "홈베이스 복귀 실패(abort) - 순찰 종료");
                safeFinish();
            }
            return;
        }

        // 현재 목표 지점에 대한 콜백만 처리
        if (currentTarget == null || !currentTarget.equals(location)) {
            return;
        }

        if (OnGoToLocationStatusChangedListener.COMPLETE.equals(status)) {
            Log.d(TAG, "도착: " + location);
            onArrived();
        } else if (OnGoToLocationStatusChangedListener.ABORT.equals(status)) {
            // 이동 실패 → 해당 지점은 건너뛰고 다음 지점으로
            Log.w(TAG, "이동 실패(abort): " + location + " - 다음 지점으로");
            locationIndex++;
            handler.post(this::startNextLeg);
        }
    }

    /** 지점 도착: 고개를 최저로 내리고 잠시 뒤 낙상 감시를 시작한다. */
    private void onArrived() {
        updatePatrolStatus("PATROL_CHECKING", currentTarget);
        setStatus(currentTarget + " 도착 - 상태 확인 중...");

        // 관찰 시작 전 서버의 전역 FallDetector 를 초기화한다.
        // (직전 순찰이 비정상 종료됐거나 종료 시 reset 이 실패해 stale 한
        //  FALL_CONFIRMED 상태가 남아, 사람이 없는데도 첫 프레임에서 즉시
        //  fall_detected=true 가 반환되는 것을 막는 안전망.)
        // HEAD_SETTLE_MS(1.5초) 뒤 관찰이 시작되므로 비동기 reset 이 적용될 시간이 확보된다.
        emergencyTriggered.set(false);
        resetFallDetectorOnServer();

        // 시선 추적을 꺼야 수동으로 고개를 숙일 수 있음
        robot.setTrackUserOn(false);
        robot.tiltAngle(HEAD_DOWN_ANGLE);
        robot.speak(TtsRequest.create("어르신 괜찮으신가요?", false));

        // 고개가 충분히 내려간 뒤 감시 시작
        handler.removeCallbacks(startObservationRunnable);
        handler.postDelayed(startObservationRunnable, HEAD_SETTLE_MS);
    }

    /** 현재 지점에서 OBSERVE_MS 동안 낙상을 감시한다. */
    private void startObservation() {
        emergencyTriggered.set(false);
        observing = true;
        updatePatrolStatus("PATROL_OBSERVING", currentTarget);
        setStatus(currentTarget + " 낙상 감시 중...");
        Log.d(TAG, "낙상 감시 시작: " + currentTarget);

        handler.removeCallbacks(endObservationRunnable);
        handler.postDelayed(endObservationRunnable, OBSERVE_MS);
    }

    /** 관찰 시간이 끝났고 낙상이 없으면 다음 지점으로. */
    private void endObservation() {
        if (!observing) {
            return; // 이미 낙상 처리로 종료됨
        }
        observing = false;
        updatePatrolStatus("PATROL_CLEAR", currentTarget);
        Log.d(TAG, "이상 없음: " + currentTarget);
        locationIndex++;
        startNextLeg();
    }

    /** 순찰 정상 종료 → 홈베이스 복귀. */
    private void finishPatrol() {
        updatePatrolStatus("RETURNING_TO_BASE", HOME_BASE);
        setStatus("순찰 완료. 홈베이스로 복귀합니다.");
        robot.speak(TtsRequest.create("순찰을 마치고 복귀합니다.", false));
        returnToBaseAndFinish();
    }

    private void returnToBaseAndFinish() {
        if (returningToBase) {
            return; // 이미 복귀 중 - 중복 호출 무시
        }
        // 다음 순찰에 이전 낙상 상태가 남아 도착 즉시 통화되는 것을 막기 위해
        // 로컬 플래그와 서버의 전역 FallDetector 상태를 모두 초기화한다.
        observing = false;
        emergencyTriggered.set(false);
        resetFallDetectorOnServer();

        // 복귀를 "시작"이 아니라 홈베이스 "도착" 시점에 끝낸다.
        // 도착 전까지 PATROL_IN_PROGRESS 가 유지되어, 복귀 이동 중에 다음 주기 알람이
        // 새 순찰을 시작해버리는 문제를 막는다. (도착/실패는 onGoToLocationStatusChanged 에서 처리)
        returningToBase = true;
        currentTarget = HOME_BASE;

        if (robot != null) {
            robot.setTrackUserOn(false);
            robot.tiltAngle(HEAD_RESET_ANGLE);
            robot.goTo(HOME_BASE);
        }

        handler.removeCallbacks(returnTimeoutRunnable);
        handler.postDelayed(returnTimeoutRunnable, RETURN_TIMEOUT_MS);
    }

    /** 홈베이스 도착(또는 타임아웃) 시 1회만 실제 종료. */
    private void safeFinish() {
        if (finishCalled) {
            return;
        }
        finishCalled = true;
        handler.removeCallbacks(returnTimeoutRunnable);
        finish();
    }

    /**
     * 서버의 전역 FallDetector 상태를 NORMAL 로 초기화한다.
     * 낙상이 한 번 확정되면 서버에 FALL_CONFIRMED 상태가 남아(프레임 전송이 멈추므로
     * 스스로 회복되지 않음) 다음 순찰 도착 즉시 fall_detected=true 가 반환되는 버그를 막는다.
     */
    private void resetFallDetectorOnServer() {
        RequestBody body = RequestBody.create(MediaType.parse("text/plain"), "");
        Request request = new Request.Builder()
                .url(SERVER_URL + "/reset_fall")
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "낙상 상태 초기화 실패: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                Log.d(TAG, "서버 낙상 상태 초기화 완료");
                response.close();
            }
        });
    }

    // ───────────────── 낙상 처리 ─────────────────────────────────────────

    /** 낙상 감지 시: 그 자리에서 보호자에게 영상통화. */
    private void handleFallDetected() {
        observing = false;
        handler.removeCallbacks(endObservationRunnable);
        updatePatrolStatus("FALL_DETECTED", currentTarget);
        setStatus("낙상 감지! 보호자에게 연락합니다.");
        Toast.makeText(this, "낙상 감지!", Toast.LENGTH_SHORT).show();
        callGuardian();
    }

    private void callGuardian() {
        robot.speak(TtsRequest.create("낙상이 감지되어 보호자에게 긴급 연락을 시도합니다.", false));

        UserInfo adminInfo = robot.getAdminInfo();
        if (adminInfo != null) {
            isCallLaunched = true;
            robot.startTelepresence(
                    adminInfo.getName(),
                    adminInfo.getUserId(),
                    com.robotemi.sdk.constants.Platform.MOBILE
            );
        } else {
            // 보호자 정보가 없으면 무한 대기하지 않고 복귀 (코드리뷰 버그 #2 방지)
            Log.e(TAG, "보호자 정보 없음 - 복귀");
            robot.speak(TtsRequest.create("연결할 보호자 연락처가 없습니다. 복귀합니다.", false));
            returnToBaseAndFinish();
        }
    }

    // ───────────────── 카메라 / 낙상 프레임 전송 ──────────────────────────

    private void updatePatrolStatus(String state, String location) {
        if (statusHeartbeat != null) {
            statusHeartbeat.update(state, location != null ? location : "patrol");
        }
    }

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
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "카메라 시작 실패: " + e.getMessage());
            } catch (CameraInfoUnavailableException | IllegalArgumentException e) {
                Log.e(TAG, "사용 가능한 카메라 없음");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private CameraSelector getAvailableCameraSelector(ProcessCameraProvider cameraProvider)
            throws CameraInfoUnavailableException {
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            return CameraSelector.DEFAULT_FRONT_CAMERA;
        }
        return CameraSelector.DEFAULT_BACK_CAMERA;
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        // 감시 중일 때만, 그리고 전송 간격을 지킬 때만 서버로 전송
        if (!observing || System.currentTimeMillis() - lastSendTime < SEND_INTERVAL_MS) {
            imageProxy.close();
            return;
        }
        lastSendTime = System.currentTimeMillis();

        try {
            byte[] nv21 = imageProxyToNV21(imageProxy);
            YuvImage yuvImage = new YuvImage(
                    nv21, ImageFormat.NV21,
                    imageProxy.getWidth(), imageProxy.getHeight(), null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                    new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 60, out);

            sendImageToServer(out.toByteArray());
        } catch (Exception e) {
            Log.e(TAG, "이미지 변환 실패: " + e.getMessage());
        } finally {
            imageProxy.close();
        }
    }

    private void sendImageToServer(byte[] jpegBytes) {
        RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), jpegBytes);

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg", imageBody)
                .build();

        // /analyze_frame 은 게임 Firebase 데이터를 건드리지 않음 (순찰 전용으로 적합)
        Request request = new Request.Builder()
                .url(SERVER_URL + "/analyze_frame")
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "서버 전송 실패: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.body() == null) {
                    return;
                }
                String result = response.body().string();
                try {
                    JSONObject json = new JSONObject(result);
                    boolean fallDetected = json.optBoolean("fall_detected", false);

                    // 감시 중 + 낙상 + 아직 미처리일 때만 1회 처리 (중복 호출 방지)
                    if (observing && fallDetected && emergencyTriggered.compareAndSet(false, true)) {
                        Log.d(TAG, "낙상 감지 at " + currentTarget);
                        runOnUiThread(PatrolActivity.this::handleFallDetected);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "JSON 파싱 실패: " + e.getMessage());
                }
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

    // ───────────────── UI ────────────────────────────────────────────────

    private void setStatus(String message) {
        runOnUiThread(() -> {
            if (textStatus != null) {
                textStatus.setText(message);
            }
        });
    }

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
}
