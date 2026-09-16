package ch.so.agi.hop.raster.geotools;

import java.nio.file.Path;

/** Full output and blockwise verification, under the runner's fixed heap. */
public final class FullRasterProbe {
  public static void main(String[] args) throws Exception {
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(backend.describe(args[0]), Path.of(args[1]), true, () -> false);
    }
    try (var source = new GeoTiffSource(new RasterDatasetRef(args[0]));
        var input = java.nio.file.Files.newInputStream(Path.of(args[1]))) {
      long rawBytes =
          (long) source.bounds().width
              * source.bounds().height
              * source.bands()
              * java.awt.image.DataBuffer.getDataTypeSize(source.dataType())
              / 8;
      byte[] header = input.readNBytes(4);
      var bytes =
          java.nio.ByteBuffer.wrap(header)
              .order(
                  header[0] == 'I'
                      ? java.nio.ByteOrder.LITTLE_ENDIAN
                      : java.nio.ByteOrder.BIG_ENDIAN);
      if (rawBytes >= 2L * 1024 * 1024 * 1024 && bytes.getShort(2) != 43)
        throw new AssertionError("Large output must be BigTIFF");
    }
    BenchmarkCompare.main(args);
  }
}
