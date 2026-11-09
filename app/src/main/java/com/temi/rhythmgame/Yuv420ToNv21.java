package com.temi.rhythmgame;

import java.nio.ByteBuffer;

final class Yuv420ToNv21 {
    private Yuv420ToNv21() { }

    static byte[] convert(int width, int height, ByteBuffer[] planes, int[] rowStrides, int[] pixelStrides) {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("YUV image dimensions must be positive and even");
        }
        byte[] output = new byte[width * height * 3 / 2];
        copy(planes[0], rowStrides[0], pixelStrides[0], width, height, output, 0, 1, width);
        copy(planes[2], rowStrides[2], pixelStrides[2], width / 2, height / 2,
                output, width * height, 2, width);
        copy(planes[1], rowStrides[1], pixelStrides[1], width / 2, height / 2,
                output, width * height + 1, 2, width);
        return output;
    }

    private static void copy(ByteBuffer source, int rowStride, int pixelStride, int width, int height,
                             byte[] output, int start, int outputStride, int outputRowStride) {
        ByteBuffer buffer = source.duplicate();
        int base = buffer.position();
        int rowLength = (width - 1) * pixelStride + 1;
        byte[] row = new byte[rowLength];
        for (int y = 0; y < height; y++) {
            buffer.position(base + y * rowStride);
            int target = start + y * outputRowStride;
            if (pixelStride == 1 && outputStride == 1) {
                buffer.get(output, target, width);
            } else {
                buffer.get(row, 0, rowLength);
                for (int x = 0; x < width; x++) output[target + x * outputStride] = row[x * pixelStride];
            }
        }
    }
}
