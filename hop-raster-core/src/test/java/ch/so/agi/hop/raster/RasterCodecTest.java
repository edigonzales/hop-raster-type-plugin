package ch.so.agi.hop.raster;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RasterCodecTest {
  static RasterDataset dataset() {
    return new RasterDataset(
        new RasterReference("/tmp/fixture.tif", "identity"),
        new RasterDescriptor(
            new RasterGrid(10, 10, 1, 0, 0, -1, .5, 9.5),
            "WKT",
            null,
            List.of(new RasterBand("elevation", 4, -9999d, 1, 0)),
            "NUMERIC",
            -1,
            false,
            List.of()),
        List.of());
  }

  @Test
  void chainRoundTripsAndOwnsCollections() throws Exception {
    var source = dataset();
    var selected = new ArrayList<>(List.of(0));
    var clip =
        new RasterOperation.Clip("clip", "POLYGON ((0 0,1 0,1 1,0 0))", true, selected, -9999d);
    selected.clear();
    var chain =
        source
            .append(clip, source.descriptor())
            .append(
                new RasterOperation.Reproject(
                    "warp", "EPSG:2056", 2, 2, List.of(), "BILINEAR", "FLOAT32", null, null),
                source.descriptor());
    assertThat(RasterCodec.decode(RasterCodec.encode(chain))).isEqualTo(chain);
    assertThat(clip.bands()).containsExactly(0);
    assertThat(RasterCodec.decode(RasterCodec.encode(null))).isNull();
  }

  @Test
  void malformedAndUnknownVersionsAreRejected() throws Exception {
    var bytes = RasterCodec.encode(dataset());
    bytes[3] = 9;
    assertThatThrownBy(() -> RasterCodec.decode(bytes)).isInstanceOf(java.io.IOException.class);
    var valid = RasterCodec.encode(dataset());
    for (int n = 0; n < valid.length; n++) {
      byte[] cut = Arrays.copyOf(valid, n);
      assertThatThrownBy(() -> RasterCodec.decode(cut)).isInstanceOf(java.io.IOException.class);
    }
    assertThatThrownBy(() -> RasterCodec.decode(Arrays.copyOf(valid, valid.length + 1)))
        .isInstanceOf(java.io.IOException.class);
  }
}
