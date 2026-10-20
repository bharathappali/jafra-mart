package com.jaframart.recs.cache;

public final class SimilarityEntry {
  public static final int EMBEDDING_DIM = 16_384;
  /** Heap bytes attributed per entry (float[16384] payload). */
  public static final long ENTRY_BYTES = (long) EMBEDDING_DIM * Float.BYTES;

  private final String[] skus;
  private final float[] embedding;

  public SimilarityEntry(String[] skus, float[] embedding) {
    this.skus = skus;
    this.embedding = embedding;
  }

  public String[] skus() {
    return skus;
  }

  public float[] embedding() {
    return embedding;
  }
}
