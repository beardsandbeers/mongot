package com.xgen.mongot.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class SanitizedBytesTest {

  @Test
  public void toString_always_returnsSanitizedPlaceholder() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});

    assertThat(sanitized.toString()).isEqualTo("xxx-sanitized-xxx");
  }

  @Test
  public void wrapAndZeroInput_validInput_zerosInput() {
    byte[] input = {10, 20, 30};

    SanitizedBytes.wrapAndZeroInput(input);

    assertThat(input).isEqualTo(new byte[] {0, 0, 0});
  }

  @Test
  public void withBytes_validInput_passesDefensiveCopy() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {10, 20, 30});

    // The callback receives a copy with the original data.
    sanitized.withBytes(
        bytes -> {
          assertThat(bytes).isEqualTo(new byte[] {10, 20, 30});
          // Mutate the copy.
          bytes[0] = 99;
        });

    // A second call still returns the original data and the mutation did not propagate.
    sanitized.withBytes(bytes -> assertThat(bytes).isEqualTo(new byte[] {10, 20, 30}));
  }

  @Test
  public void mapBytes_validInput_passesDefensiveCopy() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {10, 20, 30});

    // The callback receives a copy with the original data.
    sanitized.mapBytes(
        bytes -> {
          assertThat(bytes).isEqualTo(new byte[] {10, 20, 30});
          // Mutate the copy.
          bytes[0] = 99;
          return null;
        });

    // A second call still returns the original data and the mutation did not propagate.
    sanitized.withBytes(bytes -> assertThat(bytes).isEqualTo(new byte[] {10, 20, 30}));
  }

  @Test
  public void withBytes_consumerThrows_propagatesCheckedException() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {1});

    var thrown =
        assertThrows(
            Exception.class,
            () ->
                sanitized.withBytes(
                    bytes -> {
                      throw new Exception("test exception");
                    }));

    assertThat(thrown).hasMessageThat().isEqualTo("test exception");
  }

  @Test
  public void mapBytes_functionThrows_propagatesCheckedException() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {1});

    var thrown =
        assertThrows(
            Exception.class,
            () ->
                sanitized.mapBytes(
                    bytes -> {
                      throw new Exception("test exception");
                    }));

    assertThat(thrown).hasMessageThat().isEqualTo("test exception");
  }

  @Test
  public void mapBytes_validInput_returnsResult() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});

    int length = sanitized.mapBytes(bytes -> bytes.length);

    assertThat(length).isEqualTo(3);
  }

  @Test
  public void wrapAndZeroInput_nullInput_throws() {
    assertThrows(IllegalArgumentException.class, () -> SanitizedBytes.wrapAndZeroInput(null));
  }

  @Test
  public void equals_sameContent_areEqual() {
    var a = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});
    var b = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});

    assertThat(a).isEqualTo(b);
  }

  @Test
  public void equals_differentContent_areNotEqual() {
    var a = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});
    var b = SanitizedBytes.wrapAndZeroInput(new byte[] {4, 5, 6});

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  public void hashCode_sameContent_areEqual() {
    // Note: We only test that equal content produces equal hash codes. We do not test that
    // different content produces different hash codes because hash collisions are permitted in Java
    // hashCode implementations.
    var a = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});
    var b = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});

    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  public void withBytes_nullConsumer_throws() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {1});

    assertThrows(IllegalArgumentException.class, () -> sanitized.withBytes(null));
  }

  @Test
  public void mapBytes_nullFunction_throws() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {1});

    assertThrows(IllegalArgumentException.class, () -> sanitized.mapBytes(null));
  }

  @Test
  public void equals_null_returnsFalse() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});

    assertThat(sanitized).isNotEqualTo(null);
  }

  @Test
  public void equals_differentType_returnsFalse() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[] {1, 2, 3});

    assertThat(sanitized).isNotEqualTo("not a SanitizedBytes");
  }

  @Test
  public void wrapAndZeroInput_emptyInput_succeeds() {
    var sanitized = SanitizedBytes.wrapAndZeroInput(new byte[0]);

    sanitized.withBytes(bytes -> assertThat(bytes).isEmpty());
  }
}
