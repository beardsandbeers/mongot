package com.xgen.mongot.index.statistics;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import org.junit.Test;

public class StorageLedgerTest {
  @Test
  public void entries_exclusiveAndSharedRanges_preservesEveryPhysicalByteOnce() {
    var ledger = new StorageLedger();
    var file = new StorageLedger.File(0, "segment.cfs");
    ledger.file(file, 100);
    ledger.claim(
        file,
        10,
        30,
        new StorageLedger.Owner(
            "title", "postings", List.of("title"), List.of("$type:string/title"), false, ""));
    ledger.claim(file, 50, 20, StorageLedger.Owner.system("headers"));

    var entries = ledger.entries();

    assertThat(ledger.size()).isEqualTo(100);
    assertThat(entries.stream().mapToLong(StorageLedger.Entry::length).sum()).isEqualTo(100);
    assertThat(
            entries.stream()
                .filter(entry -> entry.owner().path().equals("title"))
                .mapToLong(StorageLedger.Entry::length)
                .sum())
        .isEqualTo(30);
    assertThat(
            entries.stream()
                .filter(entry -> entry.owner().unattributed())
                .mapToLong(StorageLedger.Entry::length)
                .sum())
        .isEqualTo(50);
  }

  @Test
  public void claim_overlappingRanges_invalidatesPhysicalFileWithoutClipping() {
    var ledger = new StorageLedger();
    var file = new StorageLedger.File(1, "segment.cfs");
    ledger.file(file, 100);
    ledger.claim(file, 10, 30, StorageLedger.Owner.system("first"));

    ledger.claim(file, 20, 30, StorageLedger.Owner.system("second"));

    assertThat(ledger.entries()).hasSize(1);
    assertThat(ledger.entries().getFirst().length()).isEqualTo(100);
    assertThat(ledger.entries().getFirst().owner().unattributed()).isTrue();
    assertThat(ledger.entries().getFirst().owner().reason()).contains("overlap");
  }

  @Test
  public void claim_outOfBounds_invalidatesPhysicalFileWithoutOverflow() {
    var ledger = new StorageLedger();
    var file = new StorageLedger.File(0, "segment.vec");
    ledger.file(file, 100);

    ledger.claim(file, Long.MAX_VALUE, Long.MAX_VALUE, StorageLedger.Owner.system("vectors"));

    assertThat(ledger.entries().getFirst().length()).isEqualTo(100);
    assertThat(ledger.entries().getFirst().owner().unattributed()).isTrue();
    assertThat(ledger.entries().getFirst().owner().reason()).contains("outside");
  }
}
