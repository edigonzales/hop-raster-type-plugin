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
}
