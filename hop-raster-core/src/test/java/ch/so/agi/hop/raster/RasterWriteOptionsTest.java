package ch.so.agi.hop.raster;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RasterWriteOptionsTest {
  @Test
  void defaultsCompressionAndKeepsCogSettings() {
    var geoTiff = RasterWriteOptions.geoTiff(" ");
    assertEquals(RasterWriteOptions.Format.GEOTIFF, geoTiff.format());
    assertEquals("Deflate", geoTiff.compression());
    assertEquals(512, geoTiff.blockSize());

    var cog = RasterWriteOptions.cog("LZW");
    assertEquals(RasterWriteOptions.Format.COG, cog.format());
    assertEquals(RasterWriteOptions.Overviews.AUTO, cog.overviews());
    assertEquals(RasterWriteOptions.Resampling.AVERAGE, cog.resampling());
  }

  @Test
  void rejectsInvalidBlockSizes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RasterWriteOptions(
                RasterWriteOptions.Format.COG,
                RasterWriteOptions.Overviews.AUTO,
                RasterWriteOptions.Resampling.NEAREST,
                500,
                "Deflate"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RasterWriteOptions(
                RasterWriteOptions.Format.COG, null, null, 8, null));
  }

  @Test
  void cogRequestsFailOnBackendsWithoutCogSupport() throws Exception {
    RasterBackend legacy =
        new RasterBackend() {
          public RasterDataset describe(String location) {
            throw new UnsupportedOperationException();
          }

          public RasterDataset derive(RasterDataset source, RasterOperation operation) {
            throw new UnsupportedOperationException();
          }

          public RasterReader open(
              RasterDataset source, java.util.function.BooleanSupplier stopped) {
            throw new UnsupportedOperationException();
          }

          public void write(
              RasterDataset source,
              java.nio.file.Path output,
              boolean overwrite,
              java.util.function.BooleanSupplier stopped) {}
        };
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            legacy.write(
                null,
                java.nio.file.Path.of("out.tif"),
                false,
                () -> false,
                RasterWriteOptions.cog("Deflate"),
                value -> {}));
    legacy.write(
        null,
        java.nio.file.Path.of("out.tif"),
        false,
        () -> false,
        RasterWriteOptions.geoTiff("Deflate"),
        value -> {});
  }
}
