package com.temi.rhythmgame;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.View;
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
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.listeners.OnBatteryStatusChangedListener;

import org.json.JSONException;
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

import com.robotemi.sdk.UserInfo;

public class MainActivity extends BaseActivity implements
        OnBatteryStatusChangedListener,
        OnGoToLocationStatusChangedListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private FillVideoView videoView;
    private TextView textStatus;
    private PreviewView previewView;
    private String gameStartTimeStr;
    private String gameEndTimeStr;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA);

    private Robot robot;
    private RobotApiClient robotApiClient;
    private SharedPreferences commandPrefs;
    private final Handler serverHandler = new Handler(Looper.getMainLooper());

    private int currentVideoType = 1;
    private ExecutorService cameraExecutor;
    private boolean mirrorOverlay = true;

    private long lastSendTime = 0;

    private boolean emergencyTriggered = false;
    private boolean fallMode = false;
    private boolean controlMode = false;
    private boolean isCalledLaunched = false;
    private boolean guardianMeetingCommand = false;
    private boolean analyzeInFlight = false;
    private boolean singleCheckRequested = false;
    private boolean gameRunning = false;
    private boolean videoConfigured = false;
    private long gameStartedAtMillis = 0;

    private int currentBattery = -1;
    private boolean currentCharging = false;
    private String robotState = "IDLE_AT_BASE";
    private String currentLocation = "home base";
    private String activeCommandId = null;
    private String activeCommandName = null;
    private String lastReceivedCommandId = null;
    private String lastCompletedCommandId = null;
    private String commandStatus = null;
    private String lastError = null;
    private JSONObject statusResult = null;

    private final Runnable commandPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollCommand();
            serverHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable statusHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            postRobotStatus();
            serverHandler.postDelayed(this, 2000);
        }
    };

    // ───────────────── 생명주기 ──────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));

        hideSystemUI();
        setContentView(R.layout.activity_main);

        robotApiClient = new RobotApiClient(ServerConfig.BASE_URL);
        commandPrefs = getSharedPreferences("robot_command_state", MODE_PRIVATE);
        lastReceivedCommandId = commandPrefs.getString("last_received_command_id", null);
        lastCompletedCommandId = commandPrefs.getString("last_completed_command_id", null);

        fallMode = getIntent().getBooleanExtra("fall_mode", false);
        controlMode = getIntent().getBooleanExtra("CONTROL_MODE", false);
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
        updateStatusText();

        if (!fallMode && !controlMode) {
            setupVideo();
        }

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        robot = Robot.getInstance();
        robot.tiltAngle(10);
        robot.setTrackUserOn(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (robot != null) {
            robot.addOnBatteryStatusChangedListener(this);
            robot.addOnGoToLocationStatusChangedListener(this);
        }
        serverHandler.post(commandPollRunnable);
        serverHandler.post(statusHeartbeatRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (robot != null) {
            robot.removeOnBatteryStatusChangedListener(this);
            robot.removeOnGoToLocationStatusChangedListener(this);
        }
        serverHandler.removeCallbacks(commandPollRunnable);
        serverHandler.removeCallbacks(statusHeartbeatRunnable);
    }

    @Override
    public void onBatteryStatusChanged(BatteryData batteryData) {
        int batteryLevel = batteryData.getBatteryPercentage();
        boolean isCharging = batteryData.isCharging();
        currentBattery = batteryLevel;
        currentCharging = isCharging;

        // 예시: 20% 이하로 떨어졌고 충전 중이 아닐 때
        if (batteryLevel <= 20 && !isCharging) {

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
        serverHandler.removeCallbacksAndMessages(null);
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
        videoConfigured = true;
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
            gameRunning = true;
            CareTaskCoordinator.setBusy(this, "RHYTHM_GAME");
            robotState = "PLAYING_GAME";
            gameStartedAtMillis = System.currentTimeMillis();
            gameStartTimeStr = sdf.format(new Date());
            Log.d(TAG, "영상 재생 시작 시간: " + gameStartTimeStr);
            updateStatusText();
        });

        videoView.setOnCompletionListener(mp -> {
            gameRunning = false;
            CareTaskCoordinator.clearBusy(this);
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
        completeActiveCommand(null);
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
            if (gameRunning || fallMode || singleCheckRequested) {
                sendImageToServer(jpegBytes);
            }

        } catch (Exception e) {
            runOnUiThread(() ->
                    textStatus.setText("Image convert failed\n" + e.getMessage())
            );
        } finally {
            imageProxy.close();
        }
    }

    private void sendImageToServer(byte[] jpegBytes) {
        if (analyzeInFlight) {
            return;
        }
        analyzeInFlight = true;
        Long elapsedMillis = gameStartedAtMillis > 0
                ? System.currentTimeMillis() - gameStartedAtMillis
                : null;

        robotApiClient.analyzeFrame(jpegBytes, elapsedMillis, new RobotApiClient.JsonCallback() {
            @Override
            public void onFailure(Exception e) {
                analyzeInFlight = false;
                lastError = e.getMessage();
                Log.e("SERVER_ERROR", e.getMessage());
                updateStatusText();
            }

            @Override
            public void onSuccess(JSONObject json) {
                analyzeInFlight = false;
                Log.d("SERVER_RESPONSE", json.toString());
                try {
                    boolean fallDetected = json.optBoolean("fall_detected", false);
                    String fallStatus = json.optString("fall_status", "NORMAL");
                    statusResult = json;

                    Log.d(
                            "CHECK",
                            "fallMode=" + fallMode +
                                    ", fallDetected=" + fallDetected +
                                    ", emergencyTriggered=" + emergencyTriggered
                    );

                    if (singleCheckRequested) {
                        singleCheckRequested = false;
                        robotState = "READY_FOR_GAME";
                        completeActiveCommand(json);
                    }

                    if ((fallDetected || "FALL_CONFIRMED".equals(fallStatus)) && gameRunning) {
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this, "낙상 의심", Toast.LENGTH_SHORT).show()
                        );
                    }

                    if (fallMode && fallDetected && !emergencyTriggered) {
                        emergencyTriggered = true;
                        Log.d("FALL_DETECTED", "낙상 감지1");

                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "낙상 감지!", Toast.LENGTH_SHORT).show();
                            callGuardian();
                        });
                    }
                    updateStatusText();
                    postRobotStatus();
                } catch (Exception e) {
                    lastError = e.getMessage();
                    Log.e("JSON_ERROR", e.getMessage());
                    updateStatusText();
                }
            }
        });
    }

    private void resetGameState() {
        robotApiClient.reset(new RobotApiClient.JsonCallback() {
            @Override
            public void onFailure(Exception e) {
                lastError = e.getMessage();
                runOnUiThread(() ->
                        textStatus.setText("게임 시작 실패\n" + e.getMessage()));
            }

            @Override
            public void onSuccess(JSONObject json) {
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

    private boolean callGuardian() {

        robot.speak(TtsRequest.create("낙상이 감지되어 보호자에게 긴급 연락을 시도합니다.", false));
        Robot robot = Robot.getInstance();

        UserInfo adminInfo = robot.getAdminInfo();

        if (adminInfo != null) {
            String targetName = adminInfo.getName();
            String targetUserId = adminInfo.getUserId();

            guardianMeetingCommand = false;
            isCalledLaunched = true;

            robot.startTelepresence(targetName, targetUserId, com.robotemi.sdk.constants.Platform.MOBILE);
            return true;


        } else {

            Log.e(
                    "CALL",
                    "보호자 정보 없음"
            );
            return false;
        }


    }

    private boolean startGuardianMeeting() {
        Robot robot = Robot.getInstance();
        UserInfo adminInfo = robot.getAdminInfo();

        if (adminInfo != null) {
            guardianMeetingCommand = true;
            isCalledLaunched = true;
            robot.startTelepresence(
                    adminInfo.getName(),
                    adminInfo.getUserId(),
                    com.robotemi.sdk.constants.Platform.MOBILE
            );
            return true;
        }

        Log.e("CALL", "Guardian information not found");
        return false;
    }

    private void pollCommand() {
        if (activeCommandId != null) {
            return;
        }

        robotApiClient.getCommand(
                ServerConfig.ROBOT_ID,
                lastReceivedCommandId,
                new RobotApiClient.JsonCallback() {
                    @Override
                    public void onSuccess(JSONObject json) {
                        if (!json.optBoolean("has_command", false)) {
                            lastError = null;
                            return;
                        }

                        JSONObject command = json.optJSONObject("command");
                        if (command == null) {
                            return;
                        }

                        String commandId = command.optString("command_id", "");
                        if (commandId.isEmpty()
                                || commandId.equals(lastReceivedCommandId)
                                || commandId.equals(lastCompletedCommandId)) {
                            return;
                        }

                        lastReceivedCommandId = commandId;
                        commandPrefs.edit()
                                .putString("last_received_command_id", commandId)
                                .apply();
                        runOnUiThread(() -> handleCommand(command));
                    }

                    @Override
                    public void onFailure(Exception e) {
                        lastError = e.getMessage();
                        updateStatusText();
                    }
                }
        );
    }

    private void handleCommand(JSONObject command) {
        activeCommandId = command.optString("command_id", null);
        activeCommandName = command.optString("command", "");
        commandStatus = "ACKNOWLEDGED";
        lastError = null;
        postRobotStatus();

        String location = command.optString("location", "");
        Log.d(TAG, "Command received: " + activeCommandName + " id=" + activeCommandId);

        switch (activeCommandName) {
            case "GO_TO_USER":
                if (location.isEmpty()) location = "거실";
                startNavigation(location, "MOVING_TO_USER");
                break;
            case "CHECK_USER":
                robotState = "CHECKING_USER";
                singleCheckRequested = true;
                commandStatus = "RUNNING";
                updateStatusText();
                postRobotStatus();
                break;
            case "START_RHYTHM_GAME":
                startGameFromCommand();
                break;
            case "START_PATROL":
                startPatrolFromCommand();
                break;
            case "STOP_GAME":
                stopGameFromCommand();
                break;
            case "RETURN_TO_BASE":
                if (location.isEmpty()) location = "home base";
                stopLocalWork();
                startNavigation(location, "RETURNING_TO_BASE");
                break;
            case "CALL_GUARDIAN":
                stopLocalWork();
                robotState = "CALLING_GUARDIAN";
                commandStatus = "RUNNING";
                updateStatusText();
                postRobotStatus();
                if (!callGuardian()) {
                    failActiveCommand("Guardian information not found");
                }
                break;
            case "MEET_GUARDIAN":
                stopLocalWork();
                robotState = "MEETING_GUARDIAN";
                commandStatus = "RUNNING";
                updateStatusText();
                postRobotStatus();
                if (!startGuardianMeeting()) {
                    failActiveCommand("Guardian information not found");
                }
                break;
            case "EMERGENCY_STOP":
                stopLocalWork();
                robotState = "EMERGENCY_STOPPED";
                commandStatus = "COMPLETED";
                if (robot != null) {
                    robot.stopMovement();
                }
                completeActiveCommand(null);
                break;
            default:
                failActiveCommand("Unknown command: " + activeCommandName);
                break;
        }
    }

    private void startNavigation(String location, String state) {
        if (location == null || location.trim().isEmpty()) {
            failActiveCommand("Location is empty");
            return;
        }

        if (robot == null) {
            failActiveCommand("Robot is not ready");
            return;
        }

        robotState = state;
        currentLocation = location;
        commandStatus = "RUNNING";
        updateStatusText();
        postRobotStatus();
        robot.goTo(location);
    }

    private void startGameFromCommand() {
        robotState = "PLAYING_GAME";
        commandStatus = "RUNNING";
        CareTaskCoordinator.setBusy(this, "RHYTHM_GAME");
        resetGameState();
        if (!videoConfigured) {
            setupVideo();
        } else if (videoView != null && !videoView.isPlaying()) {
            videoView.start();
            gameRunning = true;
            CareTaskCoordinator.setBusy(this, "RHYTHM_GAME");
            gameStartedAtMillis = System.currentTimeMillis();
        }
        completeActiveCommand(null);
    }

    private void startPatrolFromCommand() {
        stopLocalWork();
        robotState = "PATROLLING";
        commandStatus = "RUNNING";
        updateStatusText();
        postRobotStatus();

        CareTaskCoordinator.requestPatrol(this);
        completeActiveCommand(null);
    }

    private void stopGameFromCommand() {
        stopLocalWork();
        robotState = "READY_FOR_GAME";
        completeActiveCommand(null);
        CareTaskCoordinator.clearBusy(this);
        CareTaskCoordinator.runNextPendingTask(this);
    }

    private void stopLocalWork() {
        gameRunning = false;
        singleCheckRequested = false;
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
    }

    private void completeActiveCommand(JSONObject result) {
        commandStatus = "COMPLETED";
        statusResult = result != null ? result : statusResult;
        lastCompletedCommandId = activeCommandId;
        if (lastCompletedCommandId != null) {
            commandPrefs.edit()
                    .putString("last_completed_command_id", lastCompletedCommandId)
                    .apply();
        }
        postRobotStatus();
        activeCommandId = null;
        activeCommandName = null;
        commandStatus = null;
        updateStatusText();
    }

    private void failActiveCommand(String error) {
        robotState = "ERROR";
        commandStatus = "FAILED";
        lastError = error;
        postRobotStatus();
        activeCommandId = null;
        activeCommandName = null;
        updateStatusText();
    }

    private void postRobotStatus() {
        if (robotApiClient == null) {
            return;
        }

        try {
            JSONObject status = new JSONObject();
            status.put("robot_id", ServerConfig.ROBOT_ID);
            status.put("state", robotState);
            status.put("location", currentLocation);
            status.put("battery", currentBattery >= 0 ? currentBattery : JSONObject.NULL);
            status.put("charging", currentCharging);
            status.put("active_command_id", activeCommandId != null ? activeCommandId : JSONObject.NULL);
            status.put("last_completed_command_id", lastCompletedCommandId != null ? lastCompletedCommandId : JSONObject.NULL);
            status.put("command_status", commandStatus != null ? commandStatus : JSONObject.NULL);
            status.put("last_error", lastError != null ? lastError : JSONObject.NULL);
            status.put("status_result", statusResult != null ? statusResult : JSONObject.NULL);

            robotApiClient.postStatus(status, new RobotApiClient.JsonCallback() {
                @Override
                public void onSuccess(JSONObject json) {
                    lastError = null;
                }

                @Override
                public void onFailure(Exception e) {
                    lastError = e.getMessage();
                }
            });
        } catch (JSONException e) {
            lastError = e.getMessage();
        }
    }

    private void updateStatusText() {
        runOnUiThread(() -> {
            if (textStatus == null) {
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("서버: ").append(ServerConfig.BASE_URL).append('\n');
            sb.append("상태: ").append(robotState).append('\n');
            sb.append("위치: ").append(currentLocation).append('\n');
            if (activeCommandId != null) {
                sb.append("명령: ").append(activeCommandName).append(" / ").append(activeCommandId).append('\n');
            }
            if (statusResult != null) {
                sb.append("동작: ").append(statusResult.optString("detected_action", "-")).append('\n');
                sb.append("정답: ").append(statusResult.optString("expected_action", "-")).append('\n');
                sb.append("점수: ").append(statusResult.optInt("total_pose_score", 0)).append('\n');
                sb.append("낙상: ").append(statusResult.optBoolean("fall_detected", false))
                        .append(" / ").append(statusResult.optString("fall_status", "NORMAL")).append('\n');
                JSONObject counts = statusResult.optJSONObject("counts");
                if (counts != null) {
                    sb.append("L:").append(counts.optInt("LEFT_HAND_UP", 0))
                            .append(" R:").append(counts.optInt("RIGHT_HAND_UP", 0))
                            .append(" OPEN:").append(counts.optInt("ARMS_OPEN", 0))
                            .append(" CLAP:").append(counts.optInt("CLAP", 0)).append('\n');
                }
            }
            if (lastError != null) {
                sb.append("오류: ").append(lastError);
            }
            textStatus.setText(sb.toString());
        });
    }

    @Override
    public void onGoToLocationStatusChanged(
            String location,
            String status,
            int descriptionId,
            String description
    ) {
        Log.d(TAG, "goTo status: " + location + " / " + status + " / " + description);
        currentLocation = location;

        if (OnGoToLocationStatusChangedListener.COMPLETE.equals(status)) {
            if ("GO_TO_USER".equals(activeCommandName)) {
                robotState = "CHECKING_USER";
                singleCheckRequested = true;
                commandStatus = "RUNNING";
            } else if ("RETURN_TO_BASE".equals(activeCommandName)) {
                robotState = "IDLE_AT_BASE";
                completeActiveCommand(null);
            }
        } else if (OnGoToLocationStatusChangedListener.ABORT.equals(status)) {
            failActiveCommand("Navigation aborted: " + description);
        }

        updateStatusText();
        postRobotStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 영상통화 후 우리 앱으로 돌아온 경우
        if (isCalledLaunched) {

            Log.d("CALL", "영상통화 종료 확인");

            // 다음 통화를 위해 초기화
            isCalledLaunched = false;
            robotState = guardianMeetingCommand ? "IDLE_AT_BASE" : "RETURNING_TO_BASE";
            guardianMeetingCommand = false;
            commandStatus = "COMPLETED";
            completeActiveCommand(null);

            // 시작 화면으로 이동
            Intent intent = new Intent(MainActivity.this, StartActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_NEW_TASK);

            startActivity(intent);

            // home base 복귀
            if (robot != null) {
                robot.setTrackUserOn(false);
                robot.goTo("home base");
            }



            finish();
        }
    }
}

