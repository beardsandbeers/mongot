package com.xgen.mongot.index.lucene.backing;

/**
 * Value class for file metrics computed over a specific directory (recursive). These metrics are
 * not tied to any specific IndexReader, so they include temporary files and leaked segments. Since
 * computing these metrics is IO heavy, we always compute them together.
 */
public record DiskStats(long totalFileByteSize, long largestFileByteSize, long numFiles) {
  public static final DiskStats EMPTY = new DiskStats(0, 0, 0);
}
