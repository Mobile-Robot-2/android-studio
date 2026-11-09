package com.temi.rhythmgame;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.util.Log;
import android.widget.Button;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.UserInfo;
import com.robotemi.sdk.TtsRequest;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.UUID;
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

public class TestCameraActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final String sessionId = UUID.randomUUID().toString();
    private PreviewView preview;
    private TextView status;
    private SkeletonView skeleton;
    private String mode;
    private boolean mirrored;
    private boolean fallCallMode;
    private volatile boolean callStarted;
    private boolean confirmationRecorded;
    private boolean analysisErrorRecorded;
    private final FallCallGate callGate = new FallCallGate();
    private Button markFallTime;
    private long lastSent;
    private long lastRequestAt = -1;
    private volatile boolean stopped;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_test_camera);
        String requestedMode = getIntent().getStringExtra("mode");
        fallCallMode = "fall_call".equals(requestedMode);
        mode = (fallCallMode || "fall".equals(requestedMode)) ? "fall" : "rhythm";
        markFallTime = findViewById(R.id.markFallTime);
        if (fallCallMode) {
            markFallTime.setVisibility(View.VISIBLE);
            markFallTime.setOnClickListener(v -> {
                recordEvent("T0_OBSERVER_MARK", SystemClock.elapsedRealtime(), System.currentTimeMillis());
                markFallTime.setEnabled(false);
                markFallTime.setText("T0 표시됨");
            });
        }
        findViewById(R.id.fallStatusLegend).setVisibility(mode.equals("fall") ? View.VISIBLE : View.GONE);
        preview = findViewById(R.id.testPreview);
        status = findViewById(R.id.testStatus);
        skeleton = findViewById(R.id.testSkeleton);
        if (fallCallMode) {
            status.setText("시행 ID: " + sessionId.substring(0, 8) + "\n카메라 준비 중");
            recordEvent("SESSION_START", SystemClock.elapsedRealtime(), System.currentTimeMillis());
        }
        findViewById(R.id.testBack).setOnClickListener(v -> finish());
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("카메라 권한이 필요합니다.");
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (stopped) return;
            try {
                ProcessCameraProvider provider = future.get();
                Preview cameraPreview = new Preview.Builder().setTargetResolution(new Size(640, 480)).build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(executor, this::analyze);
                CameraSelector selector = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                mirrored = selector == CameraSelector.DEFAULT_FRONT_CAMERA;
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, cameraPreview, analysis);
            } catch (Exception e) {
                status.setText("카메라 오류: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (stopped || callStarted || now - lastSent < 200 || !inFlight.compareAndSet(false, true)) return;
            lastSent = now;
            try {
                byte[] bytes = jpeg(image);
                long convertedAt = SystemClock.elapsedRealtime();
                int rotation = image.getImageInfo().getRotationDegrees();
                int width = rotation == 90 || rotation == 270 ? image.getHeight() : image.getWidth();
                int height = rotation == 90 || rotation == 270 ? image.getWidth() : image.getHeight();
                send(bytes, now, convertedAt, width, height);
            } catch (Exception e) {
                inFlight.set(false);
                Log.i("PoseTiming", "session=" + sessionId + " mode=" + mode
                        + " result=conversion_error elapsed_ms=" + (SystemClock.elapsedRealtime() - now));
                show("영상 처리 오류: " + e.getMessage());
            }
        } finally {
            image.close();
        }
    }

    private byte[] jpeg(ImageProxy image) {
        int width = image.getWidth(), height = image.getHeight();
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        byte[] nv21 = Yuv420ToNv21.convert(width, height,
                new ByteBuffer[]{planes[0].getBuffer(), planes[1].getBuffer(), planes[2].getBuffer()},
                new int[]{planes[0].getRowStride(), planes[1].getRowStride(), planes[2].getRowStride()},
                new int[]{planes[0].getPixelStride(), planes[1].getPixelStride(), planes[2].getPixelStride()});
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(new Rect(0, 0, width, height), 80, stream);
        int rotation = image.getImageInfo().getRotationDegrees();
        if (rotation == 0) return stream.toByteArray();
        Bitmap original = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size());
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
        stream.reset();
        rotated.compress(Bitmap.CompressFormat.JPEG, 80, stream);
        if (rotated != original) rotated.recycle();
        original.recycle();
        return stream.toByteArray();
    }

    private void send(byte[] jpeg, long started, long convertedAt, int width, int height) {
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart("image", "frame.jpg", RequestBody.create(MediaType.parse("image/jpeg"), jpeg))
                .build();
        Request request = new Request.Builder().url(ServerConfig.BASE_URL + "/test/" + mode).post(body).build();
        long requestAt = SystemClock.elapsedRealtime();
        long requestInterval = lastRequestAt < 0 ? -1 : requestAt - lastRequestAt;
        lastRequestAt = requestAt;
        OkHttpClient measuredClient = client.newBuilder().eventListener(new okhttp3.EventListener() {
            private long bodyStarted = -1;
            private long bodyFinished = -1;
            private long responseStarted = -1;

            @Override public void requestBodyStart(@NonNull Call call) {
                bodyStarted = SystemClock.elapsedRealtime();
            }

            @Override public void requestBodyEnd(@NonNull Call call, long byteCount) {
                bodyFinished = SystemClock.elapsedRealtime();
            }

            @Override public void responseHeadersEnd(@NonNull Call call, @NonNull Response response) {
                responseStarted = SystemClock.elapsedRealtime();
            }

            @Override public void responseBodyEnd(@NonNull Call call, long byteCount) {
                logNetwork("completed");
            }

            @Override public void callFailed(@NonNull Call call, @NonNull IOException e) {
                logNetwork("failed");
            }

            private long duration(long from, long to) {
                return from < 0 || to < 0 ? -1 : to - from;
            }

            private void logNetwork(String outcome) {
                long finished = SystemClock.elapsedRealtime();
                Log.i("PoseNetwork", "session=" + sessionId + " mode=" + mode
                        + " request_ms=" + requestAt
                        + " queue_ms=" + duration(requestAt, bodyStarted)
                        + " upload_ms=" + duration(bodyStarted, bodyFinished)
                        + " wait_headers_ms=" + duration(bodyFinished, responseStarted)
                        + " receive_ms=" + duration(responseStarted, finished)
                        + " result=" + outcome);
            }
        }).build();
        measuredClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                logTiming(started, convertedAt, requestAt, requestInterval, "network_error");
                inFlight.set(false);
                if (fallCallMode) runOnUiThread(TestCameraActivity.this::recordAnalysisFailure);
                showPose(null, width, height);
                show("서버 연결 실패: " + e.getMessage());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                String timingResult = "http_" + response.code();
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        if (fallCallMode) runOnUiThread(TestCameraActivity.this::recordAnalysisFailure);
                        showPose(null, width, height);
                        show("서버 오류: HTTP " + r.code());
                        return;
                    }
                    JSONObject json = new JSONObject(r.body().string());
                    long receivedElapsed = SystemClock.elapsedRealtime();
                    long receivedWall = System.currentTimeMillis();
                    showPose(json.optJSONArray("landmarks"), width, height);
                    String text = (mode.equals("fall") ? fallText(json) : rhythmText(json))
                            + "\n응답: " + (SystemClock.elapsedRealtime() - started) + " ms";
                    if (fallCallMode) {
                        runOnUiThread(() -> {
                            handleFallCall(json, receivedElapsed, receivedWall);
                            if (!callStarted) show(text);
                        });
                    } else show(text);
                } catch (Exception e) {
                    timingResult = "response_error";
                    if (fallCallMode) runOnUiThread(TestCameraActivity.this::recordAnalysisFailure);
                    showPose(null, width, height);
                    show("응답 처리 오류: " + e.getMessage());
                } finally {
                    logTiming(started, convertedAt, requestAt, requestInterval, timingResult);
                    inFlight.set(false);
                }
            }
        });
    }

    private void logTiming(long started, long convertedAt, long requestAt, long interval, String result) {
        long finished = SystemClock.elapsedRealtime();
        Log.i("PoseTiming", "session=" + sessionId + " mode=" + mode
                + " request_interval_ms=" + interval + " conversion_ms=" + (convertedAt - started)
                + " round_trip_ms=" + (finished - requestAt) + " total_ms=" + (finished - started)
                + " result=" + result);
    }

    private void recordAnalysisFailure() {
        if (stopped || callStarted) return;
        callGate.observe(false);
        if (!analysisErrorRecorded) {
            analysisErrorRecorded = true;
            recordEvent("ANALYSIS_ERROR", SystemClock.elapsedRealtime(), System.currentTimeMillis());
        }
    }

    private void handleFallCall(JSONObject json, long receivedElapsed, long receivedWall) {
        if (stopped || callStarted) return;
        JSONObject metrics = json.optJSONObject("fall_metrics");
        boolean confirmed = json.optBoolean("detected", false)
                && json.optBoolean("fall_detected", false)
                && metrics != null && metrics.optBoolean("reliable_upper_body_visible", false);
        int result = callGate.observe(confirmed);
        if (result == 1 && !confirmationRecorded) {
            confirmationRecorded = true;
            recordEvent("T1_FALL_CONFIRMED_RECEIVED", receivedElapsed, receivedWall);
        }
        if (result != 2) return;
        callStarted = true;
        Robot robot = Robot.getInstance();
        UserInfo guardian = robot.getAdminInfo();
        if (guardian == null) {
            recordEvent("GUARDIAN_UNAVAILABLE", SystemClock.elapsedRealtime(), System.currentTimeMillis());
            show("보호자 정보가 없어 통화를 요청할 수 없습니다. 다시 시작하려면 메뉴로 돌아가세요.");
            return;
        }
        robot.speak(TtsRequest.create("낙상이 감지되어 보호자에게 연락을 시도합니다.", false));
        long requestedElapsed = SystemClock.elapsedRealtime();
        long requestedWall = System.currentTimeMillis();
        try {
            robot.startTelepresence(guardian.getName(), guardian.getUserId(),
                    com.robotemi.sdk.constants.Platform.MOBILE);
            recordEvent("T2_CALL_REQUESTED", requestedElapsed, requestedWall);
            show("보호자 영상통화를 요청했습니다. 통화 후 메뉴에서 다시 시작하세요.");
        } catch (Exception e) {
            recordEvent("T2_CALL_REQUEST_FAILED", requestedElapsed, requestedWall);
            show("보호자 영상통화 요청 실패. 메뉴에서 다시 시작하세요.");
            Log.e("FallCallTest", "Telepresence request failed", e);
        }
    }

    private void recordEvent(String event, long elapsedMillis, long wallMillis) {
        String row = sessionId + "," + event + "," + wallMillis + "," + elapsedMillis + "\n";
        Log.i("FallCallTest", row.trim());
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            Toast.makeText(this, "실험 로그 저장 실패: Logcat을 확인하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        File file = new File(dir, "fall_call_events.csv");
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            if (file.length() == 0) out.write("session_id,event,wall_time_epoch_ms,elapsed_realtime_ms\n".getBytes(StandardCharsets.UTF_8));
            out.write(row.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            Log.e("FallCallTest", "Cannot save event", e);
            Toast.makeText(this, "실험 로그 저장 실패: Logcat을 확인하세요.", Toast.LENGTH_LONG).show();
        }
    }

    private String fallText(JSONObject json) {
        String title = fallCallMode ? "보호자 연결 테스트\n시행 ID: " + sessionId.substring(0, 8) : "낙상 테스트";
        if (!json.optBoolean("detected")) return title + "\n자세 인식 불가";
        JSONObject metrics = json.optJSONObject("fall_metrics");
        return title + "\n상태: " + json.optString("fall_status", "UNKNOWN")
                + (metrics == null ? "" : "\n몸통 각도: " + metrics.optString("torso_angle", "-")
                + "°  하강량: " + metrics.optString("center_drop", "-"));
    }

    private String rhythmText(JSONObject json) {
        if (!json.optBoolean("detected")) return "리듬게임 테스트\n자세 인식 불가";
        String prediction = json.optString("detected_action", "");
        if (json.isNull("detected_action") || prediction.isEmpty()) {
            prediction = "conflict".equals(json.optString("prediction_status"))
                    ? "자세 판정 불가" : "동작 없음";
        }
        return "리듬게임 테스트\n동작 후보: " + json.optJSONArray("current_actions")
                + "\n최종 판정: " + prediction;
    }

    private void show(String message) {
        if (!stopped) runOnUiThread(() -> { if (!stopped) status.setText(message); });
    }

    private void showPose(org.json.JSONArray points, int width, int height) {
        if (!stopped) runOnUiThread(() -> {
            if (!stopped) skeleton.setPose(points, width, height, mirrored);
        });
    }

    @Override protected void onDestroy() {
        stopped = true;
        client.dispatcher().cancelAll();
        executor.shutdown();
        super.onDestroy();
    }
}
