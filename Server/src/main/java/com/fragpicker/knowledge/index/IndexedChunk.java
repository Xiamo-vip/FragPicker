package com.fragpicker.knowledge.index;

public record IndexedChunk(TextChunk text, byte[] embedding) {
    public IndexedChunk {
        if (text == null) throw new IllegalArgumentException("Missing indexed text");
        VectorCodec.decode(embedding); embedding = embedding.clone();
    }
    @Override public byte[] embedding() { return embedding.clone(); }
    @Override public String toString() { return "IndexedChunk[content=REDACTED, embedding=REDACTED]"; }
}
