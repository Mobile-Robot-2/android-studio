package com.temi.rhythmgame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

public class SkeletonView extends View {
    private static final int[][] BONES = {
            {11, 12}, {11, 13}, {13, 15}, {12, 14}, {14, 16},
            {11, 23}, {12, 24}, {23, 24}, {23, 25}, {25, 27},
            {24, 26}, {26, 28}, {27, 29}, {29, 31}, {28, 30}, {30, 32}
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private JSONArray points = new JSONArray();
    private int frameWidth, frameHeight;
    private boolean mirrored;

    public SkeletonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.GREEN);
        paint.setStrokeWidth(4 * getResources().getDisplayMetrics().density);
    }

    public void setPose(JSONArray points, int width, int height, boolean mirrored) {
        this.points = points == null ? new JSONArray() : points;
        this.frameWidth = width;
        this.frameHeight = height;
        this.mirrored = mirrored;
        invalidate();
    }

    private boolean visible(int index) {
        JSONObject point = points.optJSONObject(index);
        return point != null && (point.isNull("visibility") || point.optDouble("visibility", 0) >= 0.5);
    }

    private float x(int index, float scale, float offset) {
        double value = points.optJSONObject(index).optDouble("x");
        return offset + (float) (mirrored ? 1 - value : value) * frameWidth * scale;
    }

    private float y(int index, float scale, float offset) {
        return offset + (float) points.optJSONObject(index).optDouble("y") * frameHeight * scale;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frameWidth <= 0 || frameHeight <= 0) return;
        float scale = Math.max((float) getWidth() / frameWidth, (float) getHeight() / frameHeight);
        float left = (getWidth() - frameWidth * scale) / 2;
        float top = (getHeight() - frameHeight * scale) / 2;
        for (int[] bone : BONES) {
            if (visible(bone[0]) && visible(bone[1])) {
                canvas.drawLine(x(bone[0], scale, left), y(bone[0], scale, top),
                        x(bone[1], scale, left), y(bone[1], scale, top), paint);
            }
        }
        for (int i = 0; i < points.length(); i++) {
            if (visible(i)) canvas.drawCircle(x(i, scale, left), y(i, scale, top), 5 * getResources().getDisplayMetrics().density, paint);
        }
    }
}
