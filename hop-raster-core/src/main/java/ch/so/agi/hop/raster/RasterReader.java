package ch.so.agi.hop.raster;

public interface RasterReader extends AutoCloseable {
  RasterDescriptor descriptor();

  RasterTile read(RasterWindow window, int band) throws Exception;

  @Override
  void close();
}
