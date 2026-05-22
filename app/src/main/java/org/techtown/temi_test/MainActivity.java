package org.techtown.temi_test;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.util.Size;
import android.widget.TextView;

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
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;

    private PreviewView previewView;
    private PoseOverlayView poseOverlayView;
    private TextView textStatus;
    private ExecutorService cameraExecutor;
    private PoseDetector poseDetector;
    private boolean mirrorOverlay = true;
    private boolean isProcessingFrame = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        poseOverlayView = findViewById(R.id.poseOverlayView);
        textStatus = findViewById(R.id.textStatus);
        cameraExecutor = Executors.newSingleThreadExecutor();

        setupPoseDetector();

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

    private void setupPoseDetector() {
        PoseDetectorOptions options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();
        poseDetector = PoseDetection.getClient(options);
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

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        if (isProcessingFrame) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        isProcessingFrame = true;
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        InputImage image = InputImage.fromMediaImage(mediaImage, rotationDegrees);

        poseDetector.process(image)
                .addOnSuccessListener(pose -> handlePoseResult(pose, imageProxy))
                .addOnFailureListener(e ->
                        runOnUiThread(() -> textStatus.setText(getString(R.string.pose_failed, e.getMessage()))))
                .addOnCompleteListener(task -> {
                    isProcessingFrame = false;
                    imageProxy.close();
                });
    }

    private void handlePoseResult(Pose pose, ImageProxy imageProxy) {
        int imageWidth = imageProxy.getWidth();
        int imageHeight = imageProxy.getHeight();
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageWidth = imageProxy.getHeight();
            imageHeight = imageProxy.getWidth();
        }

        int finalImageWidth = imageWidth;
        int finalImageHeight = imageHeight;
        int detectedPeople = pose.getAllPoseLandmarks().isEmpty() ? 0 : 1;

        runOnUiThread(() -> {
            poseOverlayView.setPose(pose, finalImageWidth, finalImageHeight, mirrorOverlay);
            textStatus.setText(getString(R.string.pose_camera_detected, detectedPeople));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (poseDetector != null) {
            poseDetector.close();
            poseDetector = null;
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
