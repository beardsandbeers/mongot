package com.xgen.mongot.index.lucene;

import static com.xgen.testing.mongot.mock.index.VectorIndex.MOCK_VECTOR_DEFINITION;
import static org.mockito.Mockito.spy;

import com.xgen.mongot.index.definition.IndexDefinitionGeneration;
import com.xgen.mongot.index.definition.VectorIndexDefinitionGeneration;
import com.xgen.mongot.index.lucene.directory.IndexDirectoryHelper;
import com.xgen.mongot.index.version.Generation;
import com.xgen.mongot.index.version.IndexFormatVersion;
import com.xgen.mongot.index.version.UserIndexVersion;
import com.xgen.mongot.metrics.MetricsFactory;
import com.xgen.testing.TestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class UnsupportedIndexFormatVersionTest {
  @Test
  public void getDirectoryPath_testUnsupportedVersion_versionFour() throws IOException {
    var path = TestUtils.getTempFolder().getRoot().toPath();
    var indexDirectoryHelper =
        spy(
            IndexDirectoryHelper.create(
                path, new MetricsFactory("test", new SimpleMeterRegistry())));
    IndexDefinitionGeneration definiton =
        new VectorIndexDefinitionGeneration(
            MOCK_VECTOR_DEFINITION,
            new Generation(UserIndexVersion.FIRST, IndexFormatVersion.create(4)));
    Assert.assertThrows(
        IllegalStateException.class, () -> indexDirectoryHelper.getIndexDirectoryPath(definiton));
  }
}
