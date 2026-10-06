package com.fragpicker.knowledge.index;

import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import java.nio.*;

/** 512 normalized IEEE float32 values in little endian; no platform-dependent serialization. */
public final class VectorCodec {
    public static final int BYTES = LocalEmbeddingService.DIMENSIONS * Float.BYTES;
    private VectorCodec() { }
    public static byte[] encode(float[] vector) {
        validate(vector); var bytes = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) bytes.putFloat(value); return bytes.array();
    }
    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length != BYTES) throw new IllegalArgumentException("Invalid stored embedding length");
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); float[] vector = new float[LocalEmbeddingService.DIMENSIONS];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat(); validate(vector); return vector;
    }
    private static void validate(float[] vector) {
        if (vector == null || vector.length != LocalEmbeddingService.DIMENSIONS) throw new IllegalArgumentException("Invalid embedding dimension");
        double norm = 0;
        for (float value : vector) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid embedding value"); norm += (double) value * value; }
        if (Math.abs(norm - 1) > 0.0001) throw new IllegalArgumentException("Embedding must be unit normalized");
    }
}
