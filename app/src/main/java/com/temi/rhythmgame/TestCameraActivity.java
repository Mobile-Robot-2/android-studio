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
import android.widget.TextView;

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

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    private String mode;
    private long lastSent;
    private volatile boolean stopped;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_test_camera);
        mode = "fall".equals(getIntent().getStringExtra("mode")) ? "fall" : "rhythm";
        preview = findViewById(R.id.testPreview);
        status = findViewById(R.id.testStatus);
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
                Preview cameraPreview = new Preview.Builder().build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(executor, this::analyze);
                CameraSelector selector = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
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
            if (stopped || now - lastSent < 200 || !inFlight.compareAndSet(false, true)) return;
            lastSent = now;
            try {
                byte[] bytes = jpeg(image);
                send(bytes, now);
            } catch (Exception e) {
                inFlight.set(false);
                show("영상 처리 오류: " + e.getMessage());
            }
        } finally {
            image.close();
        }
    }

    private byte[] jpeg(ImageProxy image) {
        int width = image.getWidth(), height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int offset = 0;
        for (int p = 0; p < 3; p++) {
            int plane = p == 0 ? 0 : (p == 1 ? 2 : 1);
            ByteBuffer buffer = planes[plane].getBuffer();
            int base = buffer.position();
            int rows = p == 0 ? height : height / 2;
            int cols = p == 0 ? width : width / 2;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    int out = p == 0 ? offset++ : width * height + y * width + x * 2 + (p - 1);
                    nv21[out] = buffer.get(base + y * planes[plane].getRowStride() + x * planes[plane].getPixelStride());
                }
            }
        }
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

    private void send(byte[] jpeg, long started) {
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart("image", "frame.jpg", RequestBody.create(MediaType.parse("image/jpeg"), jpeg))
                .build();
        Request request = new Request.Builder().url(ServerConfig.BASE_URL + "/test/" + mode).post(body).build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                inFlight.set(false);
                show("서버 연결 실패: " + e.getMessage());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        show("서버 오류: HTTP " + r.code());
                        return;
                    }
                    JSONObject json = new JSONObject(r.body().string());
                    String text = mode.equals("fall") ? fallText(json) : rhythmText(json);
                    show(text + "\n응답: " + (SystemClock.elapsedRealtime() - started) + " ms");
                } catch (Exception e) {
                    show("응답 처리 오류: " + e.getMessage());
                } finally {
                    inFlight.set(false);
                }
            }
        });
    }

    private String fallText(JSONObject json) {
        if (!json.optBoolean("detected")) return "낙상 테스트\n자세 인식 불가";
        JSONObject metrics = json.optJSONObject("fall_metrics");
        return "낙상 테스트\n상태: " + json.optString("fall_status", "UNKNOWN")
                + (metrics == null ? "" : "\n몸통 각도: " + metrics.optString("torso_angle", "-")
                + "°  하강량: " + metrics.optString("center_drop", "-"));
    }

    private String rhythmText(JSONObject json) {
        if (!json.optBoolean("detected")) return "리듬게임 테스트\n자세 인식 불가";
        return "리듬게임 테스트\n동작 후보: " + json.optJSONArray("current_actions")
                + "\n최종 판정: " + (json.isNull("detected_action") ? "없음 또는 중복" : json.optString("detected_action"));
    }

    private void show(String message) {
        if (!stopped) runOnUiThread(() -> { if (!stopped) status.setText(message); });
    }

    @Override protected void onDestroy() {
        stopped = true;
        client.dispatcher().cancelAll();
        executor.shutdown();
        super.onDestroy();
    }
}
