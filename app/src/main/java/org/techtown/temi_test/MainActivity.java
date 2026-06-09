package org.techtown.temi_test;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.util.Size;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
import android.widget.Button;
//import kotlin.OptIn;

public class MainActivity extends AppCompatActivity {

    private static final String SERVER_URL = "http://172.17.66.77:8000"; //무선랜 WIFI IP4
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;

    private PreviewView previewView;
    private TextView textStatus;
    private ExecutorService cameraExecutor;
    private boolean mirrorOverlay = true;
    private final OkHttpClient client = new OkHttpClient();

    private long lastSendTime = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        textStatus = findViewById(R.id.textStatus);
        cameraExecutor = Executors.newSingleThreadExecutor();

        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v ->{
            resetGameState();
            textStatus.setText("게임 시작!");
        });


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
            textStatus.setText(R.string.pose_camera_permission);
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

//    @OptIn(markerClass = ExperimentalGetImage.class)
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

            ByteArrayOutputStream out =
                    new ByteArrayOutputStream();

            yuvImage.compressToJpeg(
                    new Rect(
                            0,
                            0,
                            imageProxy.getWidth(),
                            imageProxy.getHeight()
                    ),
                    60,
                    out
            );

            byte[] jpegBytes = out.toByteArray();

            sendImageToServer(jpegBytes);

        } catch (Exception e) {

            runOnUiThread(() ->
                    textStatus.setText(
                            "Image convert failed\n"
                                    + e.getMessage()
                    )
            );

        } finally {

            imageProxy.close();
        }
    }

    private void sendImageToServer(byte[] jpegBytes) {

        RequestBody imageBody =
                RequestBody.create(
                        MediaType.parse("image/jpeg"),
                        jpegBytes
                );

        MultipartBody requestBody =
                new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                                "file",
                                "frame.jpg",
                                imageBody
                        )
                        .build();

        Request request =
                new Request.Builder()
                        .url(SERVER_URL + "/analyze")
                        .post(requestBody)
                        .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {

                runOnUiThread(() ->
                        textStatus.setText(
                                "Server connection failed\n"
                                        + e.getMessage()
                        )
                );
            }

            @Override
            public void onResponse(
                    Call call,
                    Response response
            ) throws IOException {

                String result = response.body().string();

                runOnUiThread(() ->
                        textStatus.setText(
                                "Server Response\n"
                                        + result
                        )
                );
            }
        });
    }

    private void resetGameState() {

        RequestBody body = RequestBody.create(
                MediaType.parse("text/plain"),
                ""
        );

        Request request =
                new Request.Builder()
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
            public void onResponse(Call call, Response response)
                    throws IOException {

                runOnUiThread(() ->
                        textStatus.setText("게임 시작!"));
            }
        });
    }

    private byte[] imageProxyToNV21(ImageProxy image) { ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
    ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
    ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
    int ySize = yBuffer.remaining();
    int uSize = uBuffer.remaining();
    int vSize = vBuffer.remaining();
    byte[] nv21 = new byte[ySize + uSize + vSize];
    yBuffer.get(nv21, 0, ySize); vBuffer.get(nv21, ySize, vSize);
    uBuffer.get(nv21, ySize + vSize, uSize); return nv21; }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}