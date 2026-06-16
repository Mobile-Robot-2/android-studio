package org.techtown.temi_test;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private static final String TAG = "PoseGameAI";
    private static final String ROBOT_ID = "temi-01";
    private static final String CAMERA_IMAGE_FILE_NAME = "camera_frame.jpg";
    private static final long HEARTBEAT_INTERVAL_MS = 3000L;
    private static final MediaType EMPTY_BODY_MEDIA_TYPE =
            MediaType.parse("application/octet-stream");
    private static final MediaType JPEG_MEDIA_TYPE = MediaType.parse("image/jpeg");

    private PreviewView previewView;
    private TextView textResponse;
    private TextView textRobotStatus;
    private ImageCapture imageCapture;

    private OkHttpClient httpClient;
    private RobotApiClient robotApiClient;
    private RobotCommandPoller commandPoller;
    private RobotStateMachine stateMachine;
    private TemiNavigationManager navigationManager;
    private RhythmGameController rhythmGameController;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean captureInFlight = new AtomicBoolean(false);
    private boolean activityActive;
    private boolean serverConnected;
    private boolean fallSuspected;
    private String activeCommandId = "";
    private String activeCommandName = "";
    private String activeLocation = "";
    private String commandStatus = "IDLE";
    private String lastError = "";
    private JSONObject latestAnalysis;
    private long gameStartTimeMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        textResponse = findViewById(R.id.textResponse);
        textRobotStatus = findViewById(R.id.textRobotStatus);
        textResponse.setMovementMethod(new ScrollingMovementMethod());

        httpClient = new OkHttpClient();
        robotApiClient = new RobotApiClient(ServerConfig.BASE_URL, httpClient);
        stateMachine = new RobotStateMachine(state -> runOnUiThread(() -> {
            updateRobotStatusView();
            reportStatus(commandStatus);
        }));
        navigationManager = createNavigationManager();
        rhythmGameController = new RhythmGameController(this::captureForGame);
        commandPoller = new RobotCommandPoller(
                this,
                ROBOT_ID,
                robotApiClient,
                new RobotCommandPoller.Listener() {
                    @Override
                    public void onCommand(RobotApiClient.RobotCommand command) {
                        runOnUiThread(() -> handleRemoteCommand(command));
                    }

                    @Override
                    public void onPollingError(String message) {
                        serverConnected = false;
                        lastError = "명령 polling 실패: " + message;
                        runOnUiThread(MainActivity.this::updateRobotStatusView);
                    }
                }
        );

        Button buttonHealth = findViewById(R.id.buttonHealth);
        Button buttonReset = findViewById(R.id.buttonReset);
        Button buttonState = findViewById(R.id.buttonState);
        Button buttonAnalyzeCamera = findViewById(R.id.buttonAnalyzeCamera);

        buttonHealth.setOnClickListener(v -> sendSimpleRequest("GET", "/", null));
        buttonReset.setOnClickListener(v -> {
            gameStartTimeMs = System.currentTimeMillis();
            sendSimpleRequest("POST", "/reset", RequestBody.create(EMPTY_BODY_MEDIA_TYPE, new byte[0]));
        });
        buttonState.setOnClickListener(v -> sendSimpleRequest("GET", "/state", null));
        buttonAnalyzeCamera.setOnClickListener(v ->
                captureAndAnalyze(result -> showAnalyzeResult(result.rawJson)));

        requestCameraOrStart();
        updateRobotStatusView();
    }

    private TemiNavigationManager createNavigationManager() {
        return new TemiNavigationManager(new TemiNavigationManager.Listener() {
            @Override
            public void onNavigationStarted(String location) {
                activeLocation = location;
                commandStatus = "RUNNING";
                reportStatus(commandStatus);
                runOnUiThread(MainActivity.this::updateRobotStatusView);
            }

            @Override
            public void onNavigationCompleted(String location) {
                activeLocation = location;
                if (stateMachine.getState() == RobotStateMachine.State.MOVING_TO_USER) {
                    if (!stateMachine.transitionTo(RobotStateMachine.State.CHECKING_USER)) {
                        failActiveCommand("CHECKING_USER 상태 전환 실패");
                        return;
                    }
                    captureAndAnalyze(result -> {
                        if (result.success && !result.fallSuspected) {
                            stateMachine.transitionTo(RobotStateMachine.State.READY_FOR_GAME);
                            completeActiveCommand();
                        } else if (result.fallSuspected) {
                            handleFallEmergency();
                            failActiveCommand("낙상 의심 상태가 감지되었습니다.");
                        } else {
                            failActiveCommand("도착 후 상태 분석에 실패했습니다.");
                        }
                    });
                } else if (stateMachine.getState() == RobotStateMachine.State.RETURNING_TO_BASE) {
                    stateMachine.transitionTo(RobotStateMachine.State.IDLE_AT_BASE);
                    completeActiveCommand();
                }
            }

            @Override
            public void onNavigationFailed(String location, String message) {
                failActiveCommand("이동 실패: " + message);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityActive = true;
        navigationManager.start();
        commandPoller.start();
        heartbeatHandler.post(heartbeatRunnable);
    }

    @Override
    protected void onStop() {
        activityActive = false;
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        commandPoller.stop();
        rhythmGameController.stop();
        if (stateMachine.getState() == RobotStateMachine.State.MOVING_TO_USER
                || stateMachine.getState() == RobotStateMachine.State.RETURNING_TO_BASE) {
            navigationManager.emergencyStop();
        }
        navigationManager.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        rhythmGameController.stop();
        heartbeatHandler.removeCallbacksAndMessages(null);
        httpClient.dispatcher().cancelAll();
        super.onDestroy();
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!activityActive) {
                return;
            }
            reportStatus(commandStatus);
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private void handleRemoteCommand(RobotApiClient.RobotCommand command) {
        if ("EMERGENCY_STOP".equals(command.command)) {
            beginCommand(command);
            navigationManager.emergencyStop();
            rhythmGameController.stop();
            stateMachine.transitionTo(RobotStateMachine.State.EMERGENCY_STOPPED);
            completeActiveCommand();
            return;
        }

        if (isBusyFor(command.command)) {
            rejectCommand(command, "다른 명령을 실행 중입니다.");
            return;
        }

        beginCommand(command);
        switch (command.command) {
            case "GO_TO_USER":
                if (command.location.isEmpty()) {
                    failActiveCommand("GO_TO_USER location이 없습니다.");
                    return;
                }
                if (!stateMachine.transitionTo(RobotStateMachine.State.MOVING_TO_USER)) {
                    failActiveCommand("MOVING_TO_USER 상태 전환 실패");
                    return;
                }
                if (!navigationManager.goTo(command.location)) {
                    failActiveCommand(navigationManager.isAvailable()
                            ? "목적지를 확인할 수 없습니다."
                            : "Temi SDK가 준비되지 않았습니다.");
                }
                break;
            case "CHECK_USER":
                if (!stateMachine.transitionTo(RobotStateMachine.State.CHECKING_USER)) {
                    failActiveCommand("현재 상태에서는 상태 확인을 시작할 수 없습니다.");
                    return;
                }
                captureAndAnalyze(result -> {
                    if (!result.success) {
                        failActiveCommand("상태 분석 실패");
                    } else if (result.fallSuspected) {
                        handleFallEmergency();
                        failActiveCommand("낙상 의심 상태가 감지되었습니다.");
                    } else {
                        stateMachine.transitionTo(RobotStateMachine.State.READY_FOR_GAME);
                        completeActiveCommand();
                    }
                });
                break;
            case "START_RHYTHM_GAME":
                if (fallSuspected) {
                    failActiveCommand("낙상 의심 상태에서는 게임을 시작할 수 없습니다.");
                    return;
                }
                resetGame(() -> {
                    gameStartTimeMs = System.currentTimeMillis();
                    if (stateMachine.transitionTo(RobotStateMachine.State.PLAYING_GAME)) {
                        rhythmGameController.start();
                        completeActiveCommand();
                    } else {
                        failActiveCommand("현재 상태에서는 게임을 시작할 수 없습니다.");
                    }
                });
                break;
            case "STOP_GAME":
                stateMachine.transitionTo(RobotStateMachine.State.STOPPING_GAME);
                rhythmGameController.stop();
                stateMachine.transitionTo(RobotStateMachine.State.READY_FOR_GAME);
                completeActiveCommand();
                break;
            case "RETURN_TO_BASE":
                if (command.location.isEmpty()) {
                    failActiveCommand("RETURN_TO_BASE location이 없습니다.");
                    return;
                }
                rhythmGameController.stop();
                if (!stateMachine.transitionTo(RobotStateMachine.State.RETURNING_TO_BASE)) {
                    failActiveCommand("RETURNING_TO_BASE 상태 전환 실패");
                    return;
                }
                if (!navigationManager.goTo(command.location)) {
                    failActiveCommand(navigationManager.isAvailable()
                            ? "베이스 위치를 확인할 수 없습니다."
                            : "Temi SDK가 준비되지 않았습니다.");
                }
                break;
            default:
                failActiveCommand("지원하지 않는 명령: " + command.command);
        }
    }

    private boolean isBusyFor(String newCommand) {
        RobotStateMachine.State state = stateMachine.getState();
        if ("STOP_GAME".equals(newCommand) || "RETURN_TO_BASE".equals(newCommand)) {
            return false;
        }
        return state == RobotStateMachine.State.MOVING_TO_USER
                || state == RobotStateMachine.State.RETURNING_TO_BASE
                || state == RobotStateMachine.State.CHECKING_USER;
    }

    private void beginCommand(RobotApiClient.RobotCommand command) {
        activeCommandId = command.commandId;
        activeCommandName = command.command;
        if (!command.location.isEmpty()) {
            activeLocation = command.location;
        }
        commandStatus = "ACKNOWLEDGED";
        lastError = "";
        reportStatus(commandStatus);
        commandStatus = "RUNNING";
        reportStatus(commandStatus);
        updateRobotStatusView();
    }

    private void completeActiveCommand() {
        commandStatus = "COMPLETED";
        if (!activeCommandId.isEmpty()) {
            commandPoller.markCompleted(activeCommandId);
        }
        reportStatus(commandStatus);
        activeCommandId = "";
        activeCommandName = "";
        commandStatus = "IDLE";
        updateRobotStatusView();
    }

    private void failActiveCommand(String message) {
        lastError = message;
        commandStatus = "FAILED";
        stateMachine.transitionTo(RobotStateMachine.State.ERROR);
        if (!activeCommandId.isEmpty()) {
            commandPoller.markCompleted(activeCommandId);
        }
        reportStatus(commandStatus);
        activeCommandId = "";
        activeCommandName = "";
        commandStatus = "IDLE";
        showMessage(message);
        updateRobotStatusView();
    }

    private void rejectCommand(RobotApiClient.RobotCommand command, String message) {
        String previousId = activeCommandId;
        String previousName = activeCommandName;
        activeCommandId = command.commandId;
        activeCommandName = command.command;
        lastError = message;
        commandStatus = "FAILED";
        reportStatus(commandStatus);
        commandPoller.markCompleted(command.commandId);
        activeCommandId = previousId;
        activeCommandName = previousName;
        updateRobotStatusView();
    }

    private void requestCameraOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            lastError = "카메라 권한이 거부되었습니다.";
            showMessage(lastError);
            updateRobotStatusView();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                CameraSelector selector = getAvailableCameraSelector(provider);
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, imageCapture);
            } catch (ExecutionException | InterruptedException | IllegalArgumentException e) {
                lastError = "카메라 시작 실패: " + e.getMessage();
                showMessage(lastError);
                updateRobotStatusView();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private CameraSelector getAvailableCameraSelector(ProcessCameraProvider provider) {
        try {
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                return CameraSelector.DEFAULT_FRONT_CAMERA;
            }
        } catch (Exception ignored) {
        }
        return CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private void captureForGame(RhythmGameController.Completion completion) {
        captureAndAnalyze(result -> {
            if (result.fallSuspected) {
                handleFallEmergency();
            } else if (result.clear || result.timeOver) {
                rhythmGameController.stop();
                stateMachine.transitionTo(RobotStateMachine.State.STOPPING_GAME);
                stateMachine.transitionTo(RobotStateMachine.State.READY_FOR_GAME);
            }
            completion.onComplete();
        });
    }

    private void captureAndAnalyze(AnalyzeCallback callback) {
        if (imageCapture == null) {
            callback.onResult(AnalyzeResult.failure("카메라가 준비되지 않았습니다."));
            return;
        }
        if (!captureInFlight.compareAndSet(false, true)) {
            callback.onResult(AnalyzeResult.failure("이미 촬영 또는 분석 중입니다."));
            return;
        }

        File outputFile = new File(getCacheDir(), CAMERA_IMAGE_FILE_NAME);
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(outputFile).build();
        imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        try {
                            sendAnalyzeImageBytes(readFileBytes(outputFile), callback);
                        } catch (IOException e) {
                            captureInFlight.set(false);
                            callback.onResult(AnalyzeResult.failure(e.getMessage()));
                        } finally {
                            if (outputFile.exists()) {
                                outputFile.delete();
                            }
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        captureInFlight.set(false);
                        callback.onResult(AnalyzeResult.failure(exception.getMessage()));
                    }
                }
        );
    }

    private void sendAnalyzeImageBytes(byte[] bytes, AnalyzeCallback callback) {
        RequestBody imageBody = RequestBody.create(JPEG_MEDIA_TYPE, bytes);
        RequestBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", CAMERA_IMAGE_FILE_NAME, imageBody)
                .addFormDataPart("elapsed_time", String.valueOf(getElapsedTimeSeconds()))
                .build();
        Request request = new Request.Builder()
                .url(ServerConfig.BASE_URL + "/analyze_frame")
                .post(multipart)
                .build();

        Log.d(TAG, "POST /analyze_frame elapsed_time=" + getElapsedTimeSeconds());
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "/analyze_frame failed", e);
                captureInFlight.set(false);
                callback.onResult(AnalyzeResult.failure(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                captureInFlight.set(false);
                try {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "/analyze_frame HTTP " + response.code() + ": " + body);
                        callback.onResult(AnalyzeResult.failure(
                                "HTTP " + response.code() + ": " + body
                        ));
                        return;
                    }
                    try {
                        AnalyzeResult result = AnalyzeResult.fromJson(body);
                        latestAnalysis = result.json;
                        boolean newFallAlert = result.fallSuspected && !fallSuspected;
                        fallSuspected = result.fallSuspected;
                        serverConnected = true;
                        Log.d(TAG, "expected_action=" + result.expectedAction
                                + ", detected_action=" + result.detectedAction
                                + ", total_pose_score=" + result.totalPoseScore
                                + ", fall_status=" + result.fallStatus);
                        callback.onResult(result);
                        runOnUiThread(() -> {
                            if (newFallAlert) {
                                handleFallEmergency();
                            }
                            showAnalyzeResult(body);
                            updateRobotStatusView();
                        });
                        if (fallSuspected) {
                            reportStatus("FAILED");
                        }
                    } catch (JSONException e) {
                        callback.onResult(AnalyzeResult.failure(
                                "분석 JSON 파싱 실패: " + e.getMessage()
                        ));
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private void resetGame(Runnable onSuccess) {
        RequestBody body = RequestBody.create(EMPTY_BODY_MEDIA_TYPE, new byte[0]);
        Request request = new Request.Builder().url(ServerConfig.BASE_URL + "/reset").post(body).build();
        Log.d(TAG, "POST /reset");
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "/reset failed", e);
                runOnUiThread(() -> failActiveCommand("게임 초기화 실패: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "/reset success");
                        runOnUiThread(onSuccess);
                    } else {
                        runOnUiThread(() ->
                                failActiveCommand("게임 초기화 HTTP " + response.code()));
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private void sendSimpleRequest(String method, String path, RequestBody body) {
        Request.Builder builder = new Request.Builder().url(ServerConfig.BASE_URL + path);
        Request request = "POST".equals(method)
                ? builder.post(body).build()
                : builder.get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                serverConnected = false;
                Log.e(TAG, method + " " + path + " failed", e);
                showMessage(method + " " + path + "\n에러: " + e.getMessage());
                runOnUiThread(MainActivity.this::updateRobotStatusView);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                serverConnected = response.isSuccessful();
                Log.d(TAG, method + " " + path + " HTTP " + response.code());
                showMessage(method + " " + path
                        + "\nHTTP " + response.code()
                        + "\n\n" + responseBody);
                runOnUiThread(MainActivity.this::updateRobotStatusView);
                response.close();
            }
        });
    }

    private void reportStatus(String status) {
        JSONObject json = new JSONObject();
        try {
            json.put("robot_id", ROBOT_ID);
            json.put("state", stateMachine.getState().name());
            json.put("location", activeLocation);
            json.put("battery", JSONObject.NULL);
            json.put("active_command_id", emptyToNull(activeCommandId));
            json.put("last_completed_command_id",
                    emptyToNull(commandPoller.getLastCompletedCommandId()));
            json.put("command_status", status);
            json.put("last_error", emptyToNull(lastError));
            json.put("status_result", latestAnalysis == null ? JSONObject.NULL : latestAnalysis);
        } catch (JSONException ignored) {
            return;
        }

        robotApiClient.postStatus(json, new RobotApiClient.ResultCallback() {
            @Override
            public void onSuccess(String body) {
                serverConnected = true;
                runOnUiThread(MainActivity.this::updateRobotStatusView);
            }

            @Override
            public void onError(String message) {
                serverConnected = false;
                runOnUiThread(MainActivity.this::updateRobotStatusView);
            }
        });
    }

    private Object emptyToNull(String value) {
        return value == null || value.isEmpty() ? JSONObject.NULL : value;
    }

    private void showAnalyzeResult(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject counts = json.optJSONObject("counts");
            String warning = isFallSuspected(json) ? "\n\n[경고] 낙상 의심" : "";
            showMessage(
                    "expected_action: " + json.optString("expected_action", "-")
                            + "\ndetected_action: " + json.optString("detected_action", "-")
                            + "\naction_correct: " + json.optBoolean("action_correct", false)
                            + "\naction_score: " + json.optInt("action_score", 0)
                            + "\ntotal_pose_score: " + json.optInt("total_pose_score", 0)
                            + "\ncounts: " + (counts == null ? "{}" : counts.toString())
                            + "\nremaining_time: " + json.optString("remaining_time", "-")
                            + "\nfall_status: " + json.optString("fall_status", "UNKNOWN")
                            + "\nfall_confidence: " + json.optDouble("fall_confidence", 0.0)
                            + warning
                            + "\n\nRaw JSON:\n" + body
            );
        } catch (JSONException e) {
            showMessage(body);
        }
    }

    private boolean isFallSuspected(JSONObject json) {
        return json.optBoolean("fall_detected", false)
                || "FALL_CONFIRMED".equalsIgnoreCase(json.optString("fall_status"));
    }

    private void updateRobotStatusView() {
        JSONObject counts = latestAnalysis == null ? null : latestAnalysis.optJSONObject("counts");
        String text = "서버: " + (serverConnected ? "ONLINE" : "OFFLINE")
                + " | Polling: " + (activityActive ? "ON" : "OFF")
                + "\nTemi SDK: " + (navigationManager.isAvailable() ? "READY" : "NOT READY")
                + " | 상태: " + stateMachine.getState().name()
                + "\n위치: " + (activeLocation.isEmpty() ? "-" : activeLocation)
                + " | 명령: " + (activeCommandName.isEmpty() ? "-" : activeCommandName)
                + "\ncommand_id: " + (activeCommandId.isEmpty() ? "-" : activeCommandId)
                + "\nLEFT_HAND_UP: " + countValue(counts, "LEFT_HAND_UP")
                + " | RIGHT_HAND_UP: " + countValue(counts, "RIGHT_HAND_UP")
                + "\nARMS_OPEN: " + countValue(counts, "ARMS_OPEN")
                + " | CLAP: " + countValue(counts, "CLAP")
                + "\n동작 점수: " + (latestAnalysis == null ? 0 : latestAnalysis.optInt("total_pose_score", 0))
                + " | 남은 시간: " + (latestAnalysis == null ? "-" : latestAnalysis.optString("remaining_time", "-"))
                + "\n낙상 의심: " + (fallSuspected ? "YES" : "NO")
                + (lastError.isEmpty() ? "" : "\n마지막 오류: " + lastError);
        textRobotStatus.setBackgroundColor(
                fallSuspected ? Color.rgb(255, 205, 210) : Color.rgb(232, 240, 254)
        );
        textRobotStatus.setText(text);
    }

    private void playFallAlert() {
        Log.w(TAG, "Fall suspected: running alert placeholder");
        ToneGenerator toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200);
        heartbeatHandler.postDelayed(toneGenerator::release, 1500);
    }

    private void handleFallEmergency() {
        rhythmGameController.stop();
        navigationManager.emergencyStop();
        stateMachine.transitionTo(RobotStateMachine.State.ERROR);
        fallSuspected = true;
        lastError = "낙상 의심: 보호자 연결이 필요합니다.";
        playFallAlert();
        reportStatus("FAILED");
        updateRobotStatusView();
        // TODO: Temi 실기기에서 robot.speak(), robot.tiltAngle(), 보호자 영상통화 API를 연결합니다.
        // 이 화면에서는 의료적 진단이 아니라 "낙상 의심" 상태로만 표시합니다.
    }

    private int countValue(JSONObject counts, String key) {
        return counts == null ? 0 : counts.optInt(key, 0);
    }

    private double getElapsedTimeSeconds() {
        if (gameStartTimeMs <= 0) {
            return 0.0;
        }
        return (System.currentTimeMillis() - gameStartTimeMs) / 1000.0;
    }

    private void showMessage(String message) {
        if (!activityActive && isFinishing()) {
            return;
        }
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                textResponse.setText(message);
            }
        });
    }

    private byte[] readFileBytes(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private interface AnalyzeCallback {
        void onResult(AnalyzeResult result);
    }

    private static class AnalyzeResult {
        final boolean success;
        final boolean fallSuspected;
        final boolean clear;
        final boolean timeOver;
        final String detectedAction;
        final String expectedAction;
        final int actionScore;
        final int totalPoseScore;
        final String fallStatus;
        final double fallConfidence;
        final String rawJson;
        final JSONObject json;

        AnalyzeResult(
                boolean success,
                boolean fallSuspected,
                boolean clear,
                boolean timeOver,
                String detectedAction,
                String expectedAction,
                int actionScore,
                int totalPoseScore,
                String fallStatus,
                double fallConfidence,
                String rawJson,
                JSONObject json
        ) {
            this.success = success;
            this.fallSuspected = fallSuspected;
            this.clear = clear;
            this.timeOver = timeOver;
            this.detectedAction = detectedAction;
            this.expectedAction = expectedAction;
            this.actionScore = actionScore;
            this.totalPoseScore = totalPoseScore;
            this.fallStatus = fallStatus;
            this.fallConfidence = fallConfidence;
            this.rawJson = rawJson;
            this.json = json;
        }

        static AnalyzeResult fromJson(String body) throws JSONException {
            JSONObject json = new JSONObject(body);
            boolean fall = json.optBoolean("fall_detected", false)
                    || "FALL_CONFIRMED".equalsIgnoreCase(json.optString("fall_status"));
            return new AnalyzeResult(
                    json.optBoolean("success", true),
                    fall,
                    json.optBoolean("game_clear", false),
                    json.optBoolean("time_over", false),
                    json.optString("detected_action", null),
                    json.optString("expected_action", null),
                    json.optInt("action_score", 0),
                    json.optInt("total_pose_score", 0),
                    json.optString("fall_status", "UNKNOWN"),
                    json.optDouble("fall_confidence", 0.0),
                    body,
                    json
            );
        }

        static AnalyzeResult failure(String message) {
            return new AnalyzeResult(
                    false,
                    false,
                    false,
                    false,
                    null,
                    null,
                    0,
                    0,
                    "UNKNOWN",
                    0.0,
                    message,
                    null
            );
        }
    }
}
