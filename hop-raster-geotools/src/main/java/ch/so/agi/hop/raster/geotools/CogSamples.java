package ch.so.agi.hop.raster.geotools;

import java.awt.Rectangle;

/**
 * Read access to one overview level: either the session source or the compressed store of the level
 * below. Windows are limited to the caller's tile size, so memory stays bounded.
 */
interface CogSamples {
  int width();

  int height();

  int bands();

  int dataType();

  Double noData(int band);

  boolean valid(double value, int band);

  /**
   * Coordinates are local to this level, starting at (0, 0). Reads all bands of one window as
   * row-major doubles; window area must fit one tile.
   */
  double[][] read(int x, int y, int w, int h) throws Exception;

  /** Session source; windows must respect the reader's 262144-pixel limit. */
  final class Source implements CogSamples {
    private final RasterSource source;

    Source(RasterSource source) {
      this.source = source;
    }

    public int width() {
      return source.bounds().width;
    }

    public int height() {
      return source.bounds().height;
    }

    public int bands() {
      return source.bands();
    }

    public int dataType() {
      return source.dataType();
    }

    public Double noData(int band) {
      return source.noData(band);
    }

    public boolean valid(double value, int band) {
      return source.valid(value, band);
    }

    public double[][] read(int x, int y, int w, int h) throws Exception {
      double[][] result = new double[source.bands()][];
      for (int band = 0; band < source.bands(); band++) {
        var raster =
            source.read(
                new RasterReadRequest(
                    new Rectangle(source.bounds().x + x, source.bounds().y + y, w, h), band));
        result[band] =
            raster.getSamples(raster.getMinX(), raster.getMinY(), w, h, 0, (double[]) null);
      }
      return result;
    }
  }

  /** Compressed overview store of the level below. */
  final class Store implements CogSamples {
    private final CogBlockStore store;
    private final Rectangle bounds;
    private final int dataType;
    private final int bands;
    private final Double[] noData;
    private final int block;
    private final CogTileCodec codec;
    private final int tilesAcross;
    private byte[] compressed = new byte[1024];
    private byte[] raw = new byte[0];

    Store(
        CogBlockStore store,
        int width,
        int height,
        int dataType,
        int bands,
        Double[] noData,
        int block,
        CogTileCodec codec) {
      this.store = store;
      this.bounds = new Rectangle(width, height);
      this.dataType = dataType;
      this.bands = bands;
      this.noData = noData.clone();
      this.block = block;
      this.codec = codec;
      this.tilesAcross = (width + block - 1) / block;
    }

    public int width() {
      return bounds.width;
    }

    public int height() {
      return bounds.height;
    }

    public int bands() {
      return bands;
    }

    public int dataType() {
      return dataType;
    }

    public Double noData(int band) {
      return noData[band];
    }

    public boolean valid(double value, int band) {
      return Double.isFinite(value) && (noData[band] == null || value != noData[band]);
    }

    public double[][] read(int x, int y, int w, int h) throws Exception {
      int tile = (y / block) * tilesAcross + x / block;
      int length = store.length(tile);
      if (compressed.length < length) compressed = new byte[length];
      store.read(tile, compressed);
      int expected = block * block * bands * CogTile.sampleBytes(dataType);
      if (raw.length < expected) raw = new byte[expected];
      codec.decode(compressed, 0, length, raw);
      int localX = x - (x / block) * block;
      int localY = y - (y / block) * block;
      int bytes = CogTile.sampleBytes(dataType);
      double[][] result = new double[bands][w * h];
      for (int band = 0; band < bands; band++)
        for (int yy = 0; yy < h; yy++)
          for (int xx = 0; xx < w; xx++) {
            int sample = ((localY + yy) * block + localX + xx) * bands + band;
            result[band][yy * w + xx] = CogTile.sample(raw, sample, dataType, bytes);
          }
      return result;
    }
  }
}
