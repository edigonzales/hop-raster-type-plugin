package ch.so.agi.hop.raster.type;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.hop.raster.*;
import java.io.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValueMetaRasterTest {
  @Test
  void streamCloneComparisonAndInvalidConversion() throws Exception {
    var value =
        new RasterDataset(
            new RasterReference("/tmp/input.tif", null),
            new RasterDescriptor(
                new RasterGrid(2, 2, 1, 0, 0, -1, .5, 1.5),
                null,
                null,
                List.of(new RasterBand("band", 5, Double.NaN, 1, 0)),
                "NUMERIC",
                -1,
                false,
                List.of()),
            List.of());
    var meta = new ValueMetaRaster("raster");
    var bytes = new ByteArrayOutputStream();
    meta.writeData(new DataOutputStream(bytes), value);
    var decoded = meta.readData(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    assertThat(decoded).isEqualTo(value);
    assertThat(meta.compare(value, decoded)).isZero();
    assertThat(meta.hashCode(value)).isEqualTo(meta.hashCode(decoded));
    assertThat(meta.cloneValueData(value)).isSameAs(value);
    var other =
        new RasterDataset(
            new RasterReference("/tmp/other.tif", null), value.descriptor(), List.of());
    assertThat(meta.compare(value, other)).isNegative();
    meta.setSortedDescending(true);
    assertThat(meta.compare(value, other)).isPositive();
    assertThatThrownBy(() -> meta.getRaster("input.tif")).hasMessageContaining("Raster Reader");
  }
}
