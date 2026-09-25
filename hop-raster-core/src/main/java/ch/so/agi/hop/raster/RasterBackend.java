package ch.so.agi.hop.raster;

public interface RasterBackend {
  RasterDataset describe(String location) throws Exception;

  RasterDataset derive(RasterDataset source, RasterOperation operation) throws Exception;

  RasterReader open(RasterDataset source, java.util.function.BooleanSupplier stopped)
      throws Exception;

  void write(
      RasterDataset source,
      java.nio.file.Path output,
      boolean overwrite,
      java.util.function.BooleanSupplier stopped)
      throws Exception;

  /**
   * Writes with an explicit TIFF compression and reports encoder progress from 0 to 100.
   * Backends that predate this overload keep their original behavior.
   */
  default void write(
      RasterDataset source,
      java.nio.file.Path output,
      boolean overwrite,
      java.util.function.BooleanSupplier stopped,
      String compression,
      java.util.function.IntConsumer progress)
      throws Exception {
    write(source, output, overwrite, stopped);
  }

  /**
   * Writes with explicit format options and reports encoder progress from 0 to 100. Backends that
   * predate this overload keep their original behavior for plain GeoTIFF requests and reject COG
   * output instead of silently writing a file without the requested structure.
   */
  default void write(
      RasterDataset source,
      java.nio.file.Path output,
      boolean overwrite,
      java.util.function.BooleanSupplier stopped,
      RasterWriteOptions options,
      java.util.function.IntConsumer progress)
      throws Exception {
    if (options.format() == RasterWriteOptions.Format.COG)
      throw new UnsupportedOperationException(
          "COG output requires a newer raster backend: " + getClass().getName());
    write(source, output, overwrite, stopped, options.compression(), progress);
  }
}
