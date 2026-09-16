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
}
