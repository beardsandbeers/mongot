package com.xgen.mongot.index.statistics;

import com.google.errorprone.annotations.Var;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Disjoint physical ranges. Invalid claims invalidate the entire affected physical file. */
public final class StorageLedger {
  public record File(int partition, String name) {}

  public record Owner(
      String path,
      String component,
      List<String> paths,
      List<String> physicalFields,
      boolean unattributed,
      String reason) {
    public static Owner unknown(String reason) {
      return new Owner("", "unsupported", List.of(), List.of(), true, reason);
    }

    public static Owner system(String component) {
      return new Owner("", component, List.of(), List.of(), false, "index-owned metadata");
    }
  }

  public record Entry(File file, long offset, long length, Owner owner) {}

  private static final class PhysicalFile {
    private final long length;
    private final TreeMap<Long, Entry> claims = new TreeMap<>();
    private Owner remainder = Owner.unknown("no supported ownership metadata");
    private boolean invalid;

    private PhysicalFile(long length) {
      if (length < 0) {
        throw new IllegalArgumentException("Negative file length");
      }
      this.length = length;
    }
  }

  private final Map<File, PhysicalFile> files = new HashMap<>();
  private int ranges;
  private static final int MAX_ITEMS = 200_000;

  public void file(File file, long length) {
    if (this.files.size() >= MAX_ITEMS && !this.files.containsKey(file)) {
      throw new StatisticsLimitException("Statistics exceed the physical file budget");
    }
    var previous = this.files.putIfAbsent(file, new PhysicalFile(length));
    if (previous != null && previous.length != length) {
      throw new IllegalArgumentException("Snapshot file length changed: " + file.name());
    }
  }

  public void remainder(File file, Owner owner) {
    var physical = this.files.get(file);
    if (!physical.invalid) {
      physical.remainder = owner;
    }
  }

  public void invalidate(File file, String reason) {
    var physical = this.files.get(file);
    physical.invalid = true;
    this.ranges -= physical.claims.size();
    physical.claims.clear();
    physical.remainder = Owner.unknown(reason);
  }

  /** Claims a verified exclusive range; invalidates the file on conflicts or invalid bounds. */
  public void claim(File file, long offset, long length, Owner owner) {
    var physical = this.files.get(file);
    if (physical.invalid) {
      return;
    }
    if (offset < 0 || length < 0 || offset > physical.length || length > physical.length - offset) {
      invalidate(file, "codec range is outside its physical file");
      return;
    }
    if (length == 0) {
      return;
    }
    var before = physical.claims.floorEntry(offset);
    var after = physical.claims.ceilingEntry(offset);
    if ((before != null && before.getValue().offset() + before.getValue().length() > offset)
        || (after != null && after.getKey() < offset + length)) {
      invalidate(file, "codec ranges overlap");
      return;
    }
    if (this.ranges >= MAX_ITEMS) {
      throw new StatisticsLimitException("Statistics exceed the byte range budget");
    }
    this.ranges++;
    physical.claims.put(offset, new Entry(file, offset, length, owner));
  }

  public long size() {
    return this.files.values().stream().mapToLong(file -> file.length).reduce(0, Math::addExact);
  }

  /** Returns the disjoint ownership ledger, including every unclaimed gap exactly once. */
  public List<Entry> entries() {
    var result = new ArrayList<Entry>();
    for (var item : this.files.entrySet()) {
      @Var long offset = 0;
      for (var claim : item.getValue().claims.values()) {
        if (claim.offset() > offset) {
          result.add(
              new Entry(item.getKey(), offset, claim.offset() - offset, item.getValue().remainder));
        }
        result.add(claim);
        offset = claim.offset() + claim.length();
      }
      if (offset < item.getValue().length) {
        result.add(
            new Entry(
                item.getKey(), offset, item.getValue().length - offset, item.getValue().remainder));
      }
    }
    result.sort(
        Comparator.comparingInt((Entry e) -> e.file().partition())
            .thenComparing(e -> e.file().name())
            .thenComparingLong(Entry::offset));
    return List.copyOf(result);
  }
}
