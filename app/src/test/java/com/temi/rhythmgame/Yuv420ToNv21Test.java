package com.temi.rhythmgame;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class Yuv420ToNv21Test {
    @Test public void handlesPaddingPixelStrideAndBufferPosition() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{99, 1, 2, 3, 4, 99, 5, 6, 7, 8});
        ByteBuffer u = ByteBuffer.wrap(new byte[]{99, 21, 99, 22});
        ByteBuffer v = ByteBuffer.wrap(new byte[]{99, 31, 99, 32});
        y.position(1); u.position(1); v.position(1);
        byte[] result = Yuv420ToNv21.convert(4, 2, new ByteBuffer[]{y, u, v},
                new int[]{5, 4, 4}, new int[]{1, 2, 2});
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 31, 21, 32, 22}, result);
        assertEquals(1, y.position());
        assertEquals(1, u.position());
        assertEquals(1, v.position());
    }

    @Test public void handlesPlanarChromaAndMultipleRows() {
        byte[] result = Yuv420ToNv21.convert(2, 4,
                new ByteBuffer[]{ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}),
                        ByteBuffer.wrap(new byte[]{11, 12}), ByteBuffer.wrap(new byte[]{21, 22})},
                new int[]{2, 1, 1}, new int[]{1, 1, 1});
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 21, 11, 22, 12}, result);
    }
}
