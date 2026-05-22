package org.techtown.temi_test;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

import java.util.List;

public class PoseOverlayView extends View {

    private static final int[][] POSE_CONNECTIONS = new int[][]{
            {PoseLandmark.NOSE, PoseLandmark.LEFT_EYE_INNER},
            {PoseLandmark.LEFT_EYE_INNER, PoseLandmark.LEFT_EYE},
            {PoseLandmark.LEFT_EYE, PoseLandmark.LEFT_EYE_OUTER},
            {PoseLandmark.LEFT_EYE_OUTER, PoseLandmark.LEFT_EAR},
            {PoseLandmark.NOSE, PoseLandmark.RIGHT_EYE_INNER},
            {PoseLandmark.RIGHT_EYE_INNER, PoseLandmark.RIGHT_EYE},
            {PoseLandmark.RIGHT_EYE, PoseLandmark.RIGHT_EYE_OUTER},
            {PoseLandmark.RIGHT_EYE_OUTER, PoseLandmark.RIGHT_EAR},
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER},
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW},
            {PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW},
            {PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST},
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP},
            {PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP},
            {PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE},
            {PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE},
            {PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE},
            {PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE},
            {PoseLandmark.LEFT_WRIST, PoseLandmark.LEFT_THUMB},
            {PoseLandmark.LEFT_WRIST, PoseLandmark.LEFT_INDEX},
            {PoseLandmark.LEFT_WRIST, PoseLandmark.LEFT_PINKY},
            {PoseLandmark.RIGHT_WRIST, PoseLandmark.RIGHT_THUMB},
            {PoseLandmark.RIGHT_WRIST, PoseLandmark.RIGHT_INDEX},
            {PoseLandmark.RIGHT_WRIST, PoseLandmark.RIGHT_PINKY}
    };

    private final Paint linePaint = new Paint();
    private final Paint pointPaint = new Paint();
    private Pose pose;
    private int imageWidth = 1;
    private int imageHeight = 1;
    private boolean mirror;

    public PoseOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        linePaint.setColor(Color.GREEN);
        linePaint.setStrokeWidth(8f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setAntiAlias(true);

        pointPaint.setColor(Color.RED);
        pointPaint.setStrokeWidth(10f);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAntiAlias(true);
    }

    public void setPose(Pose pose, int imageWidth, int imageHeight, boolean mirror) {
        this.pose = pose;
        this.imageWidth = Math.max(imageWidth, 1);
        this.imageHeight = Math.max(imageHeight, 1);
        this.mirror = mirror;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (pose == null || pose.getAllPoseLandmarks().isEmpty()) {
            return;
        }

        float scale = Math.max(
                getWidth() / (float) imageWidth,
                getHeight() / (float) imageHeight
        );
        float offsetX = (getWidth() - imageWidth * scale) / 2f;
        float offsetY = (getHeight() - imageHeight * scale) / 2f;

        drawConnections(canvas, scale, offsetX, offsetY);
        drawPoints(canvas, pose.getAllPoseLandmarks(), scale, offsetX, offsetY);
    }

    private void drawConnections(Canvas canvas, float scale, float offsetX, float offsetY) {
        for (int[] connection : POSE_CONNECTIONS) {
            PoseLandmark start = pose.getPoseLandmark(connection[0]);
            PoseLandmark end = pose.getPoseLandmark(connection[1]);
            if (start == null || end == null) {
                continue;
            }

            canvas.drawLine(
                    translateX(start.getPosition().x, scale, offsetX),
                    translateY(start.getPosition().y, scale, offsetY),
                    translateX(end.getPosition().x, scale, offsetX),
                    translateY(end.getPosition().y, scale, offsetY),
                    linePaint
            );
        }
    }

    private void drawPoints(
            Canvas canvas,
            List<PoseLandmark> landmarks,
            float scale,
            float offsetX,
            float offsetY
    ) {
        for (PoseLandmark landmark : landmarks) {
            canvas.drawCircle(
                    translateX(landmark.getPosition().x, scale, offsetX),
                    translateY(landmark.getPosition().y, scale, offsetY),
                    8f,
                    pointPaint
            );
        }
    }

    private float translateX(float x, float scale, float offsetX) {
        float mappedX = mirror ? imageWidth - x : x;
        return offsetX + mappedX * scale;
    }

    private float translateY(float y, float scale, float offsetY) {
        return offsetY + y * scale;
    }
}
