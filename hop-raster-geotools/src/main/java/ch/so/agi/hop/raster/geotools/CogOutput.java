package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.RasterWriteOptions;
import it.geosolutions.imageio.plugins.tiff.TIFFDirectory;
import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageio.plugins.tiff.TIFFTag;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BandedSampleModel;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import javax.imageio.ImageWriteParam;
import org.geotools.gce.geotiff.GeoTiffWriteParams;

/**
 * Writes a Cloud Optimized GeoTIFF: tiled image with internal overviews, IFDs and tag values
 * before the tile data, overview data before the main image data, GDAL-compatible ghost area and
 * block leaders/trailers. Overviews are generated into compressed temporary stores and are
 * compressed only once.
 */
final class CogOutput {
  private static final String GHOST_TEXT =
      "LAYOUT=IFDS_BEFORE_DATA\n"
          + "BLOCK_ORDER=ROW_MAJOR\n"
          + "BLOCK_LEADER=SIZE_AS_UINT4\n"
          + "BLOCK_TRAILER=LAST_4_BYTES_REPEATED\n"
          + "KNOWN_INCOMPATIBLE_EDITION=NO\n ";

  private CogOutput() {}

  static void write(
      Path target,
      RasterSource source,
      RasterWriteOptions options,
      boolean bigTiff,
      BooleanSupplier stopped,
      IntConsumer progress)
      throws Exception {
    RasterColorInfo color = source.colorInfo();
    if (color.kind() == RasterColorInfo.Kind.UNSUPPORTED)
      throw new IllegalArgumentException("Unsupported color interpretation");
    int bands = source.bands();
    int dataType = source.dataType();
    int block = options.blockSize();
    Rectangle bounds = source.bounds();
    if (bounds.width <= 0 || bounds.height <= 0)
      throw new IllegalArgumentException("Empty raster source");
    Double noData = source.noData(0);
    for (int band = 1; band < bands; band++)
      if (!Objects.equals(noData, source.noData(band)))
        throw new IllegalArgumentException("GeoTIFF writer requires a common NoData sentinel");
    double[] scales = new double[bands], offsets = new double[bands];
    for (int band = 0; band < bands; band++) {
      scales[band] = source.scale(band);
      offsets[band] = source.offset(band);
    }
    if (!(source.gridToWorld() instanceof AffineTransform gridToWorld))
      throw new IllegalArgumentException("Only affine raster grids are supported");
    int[] bits = new int[bands], formats = new int[bands];
    for (int band = 0; band < bands; band++) {
      bits[band] = CogTile.bitsPerSample(dataType);
      formats[band] = CogTile.sampleFormat(dataType);
    }
    RasterWriteOptions.Resampling resampling =
        color.kind() == RasterColorInfo.Kind.PALETTE
            ? RasterWriteOptions.Resampling.NEAREST
            : options.resampling();

    List<int[]> levels =
        options.overviews() == RasterWriteOptions.Overviews.AUTO
            ? planLevels(bounds.width, bounds.height, block)
            : List.of();
    long overviewTiles = 0;
    for (int[] level : levels) overviewTiles += tileCount(level[0], level[1], block);
    long mainTiles = tileCount(bounds.width, bounds.height, block);
    Work work = new Work(progress, overviewTiles, overviewTiles + mainTiles);

    try (CogTileCodec codec = CogTileCodec.create(options.compression(), bands, bits, formats)) {
      List<CogBlockStore> stores = new ArrayList<>();
      try {
        CogSamples previous = new CogSamples.Source(source);
        Double[] noDataByBand = new Double[bands];
        Arrays.fill(noDataByBand, noData);
        for (int[] level : levels) {
          checkStopped(stopped);
          CogBlockStore store =
              generate(
                  previous,
                  level,
                  resampling,
                  block,
                  bands,
                  dataType,
                  noDataByBand,
                  codec,
                  target.getParent(),
                  stopped,
                  work);
          stores.add(store);
          previous =
              new CogSamples.Store(
                  store, level[0], level[1], dataType, bands, noDataByBand, block, codec);
        }

        CogTiff.Directory main =
            mainDirectory(
                source, color, dataType, bands, bounds, gridToWorld, scales, offsets, noData, block,
                bigTiff, codec);
        main.tileOffsets = new long[(int) mainTiles];
        main.tileByteCounts = new long[(int) mainTiles];
        List<CogTiff.Directory> directories = new ArrayList<>();
        List<CogTiff.Directory> overviewDirectories = new ArrayList<>();
        directories.add(main);
        for (int[] level : levels) {
          CogTiff.Directory directory = main.copy();
          patchOverview(directory, level[0], level[1], block);
          directory.tileOffsets = new long[(int) tileCount(level[0], level[1], block)];
          directory.tileByteCounts = new long[directory.tileOffsets.length];
          directories.add(directory);
          overviewDirectories.add(directory);
        }

        String ghost =
            String.format(
                    Locale.ROOT, "GDAL_STRUCTURAL_METADATA_SIZE=%06d bytes\n", GHOST_TEXT.length())
                + GHOST_TEXT;
        try (CogTiff tiff = new CogTiff(target, bigTiff, directories, ghost)) {
          tiff.open();
          byte[] compressed = new byte[1024];
          for (int level = stores.size() - 1; level >= 0; level--) {
            CogBlockStore store = stores.get(level);
            CogTiff.Directory directory = overviewDirectories.get(level);
            for (int tile = 0; tile < store.size(); tile++) {
              checkStopped(stopped);
              int length = store.length(tile);
              if (compressed.length < length) compressed = new byte[length];
              store.read(tile, compressed);
              long offset = tiff.writeBlock(compressed, length);
              directory.tileOffsets[tile] = offset;
              directory.tileByteCounts[tile] = length;
              work.assembled();
            }
          }
          writeMainImage(tiff, source, main, block, bands, dataType, codec, stopped, work);
          tiff.writeFrontArea();
        }
      } finally {
        for (CogBlockStore store : stores) store.close();
      }
    }
    if (progress != null) progress.accept(100);
  }

  private static void writeMainImage(
      CogTiff tiff,
      RasterSource source,
      CogTiff.Directory directory,
      int block,
      int bands,
      int dataType,
      CogTileCodec codec,
      BooleanSupplier stopped,
      Work work)
      throws Exception {
    Rectangle bounds = source.bounds();
    int across = (bounds.width + block - 1) / block;
    int down = (bounds.height + block - 1) / block;
    int bytes = CogTile.sampleBytes(dataType);
    double[][] padded = new double[bands][block * block];
    byte[] raw = new byte[block * block * bands * bytes];
    CogTileBuffer buffer = new CogTileBuffer();
    CogSamples samples = new CogSamples.Source(source);
    int tile = 0;
    for (int ty = 0; ty < down; ty++) {
      for (int tx = 0; tx < across; tx++) {
        checkStopped(stopped);
        int width = Math.min(block, bounds.width - tx * block);
        int height = Math.min(block, bounds.height - ty * block);
        double[][] window =
            samples.read(bounds.x + tx * block, bounds.y + ty * block, width, height);
        for (int band = 0; band < bands; band++) {
          for (int row = 0; row < height; row++) {
            System.arraycopy(window[band], row * width, padded[band], row * block, width);
            Arrays.fill(padded[band], row * block + width, row * block + block, 0);
          }
          if (height < block)
            Arrays.fill(padded[band], height * block, padded[band].length, 0);
        }
        CogTile.pack(padded, block, bands, dataType, raw);
        buffer.reset();
        int length;
        try (var stream = buffer.open()) {
          length = codec.encode(stream, raw, block, block, block * bands * bytes);
        }
        long offset = tiff.writeBlock(buffer.data(), length);
        directory.tileOffsets[tile] = offset;
        directory.tileByteCounts[tile] = length;
        tile++;
        work.assembled();
      }
    }
  }

  /** Generates one overview level into a compressed temporary store. */
  private static CogBlockStore generate(
      CogSamples previous,
      int[] dims,
      RasterWriteOptions.Resampling resampling,
      int block,
      int bands,
      int dataType,
      Double[] noData,
      CogTileCodec codec,
      Path directory,
      BooleanSupplier stopped,
      Work work)
      throws Exception {
    int width = dims[0], height = dims[1];
    int across = (width + block - 1) / block;
    int down = (height + block - 1) / block;
    CogBlockStore store = CogBlockStore.create(directory, ".hop-raster-ovr-", across * down);
    try {
      int bytes = CogTile.sampleBytes(dataType);
      byte[] raw = new byte[block * block * bands * bytes];
      double[][] output = new double[bands][block * block];
      double[][] sums = new double[bands][block * block];
      int[][] counts = new int[bands][block * block];
      boolean average = resampling == RasterWriteOptions.Resampling.AVERAGE;
      CogTileBuffer buffer = new CogTileBuffer();
      for (int ty = 0; ty < down; ty++) {
        for (int tx = 0; tx < across; tx++) {
          checkStopped(stopped);
          int originX = tx * 2 * block, originY = ty * 2 * block;
          for (int band = 0; band < bands; band++) {
            Arrays.fill(output[band], 0);
            if (average) {
              Arrays.fill(sums[band], 0);
              Arrays.fill(counts[band], 0);
            }
          }
          for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
              int sx = originX + dx * block, sy = originY + dy * block;
              int sw = Math.min(block, previous.width() - sx);
              int sh = Math.min(block, previous.height() - sy);
              if (sw <= 0 || sh <= 0) continue;
              double[][] input = previous.read(sx, sy, sw, sh);
              for (int band = 0; band < bands; band++) {
                if (average) {
                  for (int row = 0; row < sh; row++) {
                    for (int column = 0; column < sw; column++) {
                      double value = input[band][row * sw + column];
                      if (!previous.valid(value, band)) continue;
                      int ox = (sx + column) / 2 - tx * block;
                      int oy = (sy + row) / 2 - ty * block;
                      sums[band][oy * block + ox] += value;
                      counts[band][oy * block + ox]++;
                    }
                  }
                } else {
                  for (int row = (sy & 1) == 0 ? 0 : 1; row < sh; row += 2) {
                    for (int column = ((sx & 1) == 0 ? 0 : 1); column < sw; column += 2) {
                      int ox = (sx + column) / 2 - tx * block;
                      int oy = (sy + row) / 2 - ty * block;
                      output[band][oy * block + ox] = input[band][row * sw + column];
                    }
                  }
                }
              }
            }
          }
          if (average) {
            for (int band = 0; band < bands; band++) {
              for (int pixel = 0; pixel < block * block; pixel++) {
                if (counts[band][pixel] == 0) {
                  output[band][pixel] = invalid(noData[band], dataType);
                } else {
                  double value = sums[band][pixel] / counts[band][pixel];
                  if (dataType != DataBuffer.TYPE_FLOAT && dataType != DataBuffer.TYPE_DOUBLE)
                    value = Math.rint(value);
                  output[band][pixel] = value;
                }
              }
            }
          }
          CogTile.pack(output, block, bands, dataType, raw);
          buffer.reset();
          int length;
          try (var stream = buffer.open()) {
            length = codec.encode(stream, raw, block, block, block * bands * bytes);
          }
          store.append(buffer.data(), length);
          work.overview();
        }
      }
      return store;
    } catch (Exception e) {
      store.close();
      throw e;
    }
  }

  private static double invalid(Double noData, int dataType) {
    if (noData != null) return noData;
    if (dataType == DataBuffer.TYPE_FLOAT || dataType == DataBuffer.TYPE_DOUBLE) return Double.NaN;
    return 0;
  }

  private static CogTiff.Directory mainDirectory(
      RasterSource source,
      RasterColorInfo color,
      int dataType,
      int bands,
      Rectangle bounds,
      AffineTransform gridToWorld,
      double[] scales,
      double[] offsets,
      Double noData,
      int block,
      boolean bigTiff,
      CogTileCodec codec)
      throws Exception {
    GeoTiffWriteParams writeParams = new GeoTiffWriteParams();
    if ("None".equalsIgnoreCase(codec.name())) {
      writeParams.setCompressionMode(ImageWriteParam.MODE_DISABLED);
    } else {
      writeParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      writeParams.setCompressionType(codec.name());
    }
    writeParams.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
    writeParams.setTiling(block, block);
    writeParams.setForceToBigTIFF(bigTiff);
    var colorModel = GeoTiffOutput.colorModel(dataType, bands, color);
    var sampleModel = new BandedSampleModel(dataType, block, block, bands);
    var type = new javax.imageio.ImageTypeSpecifier(colorModel, sampleModel);
    TIFFDirectory metadata =
        GeoTiffOutput.directory(
            type,
            bands,
            dataType,
            bounds.x,
            bounds.y,
            source.crs(),
            gridToWorld,
            scales,
            offsets,
            noData,
            color,
            writeParams);
    CogTiff.Directory directory = new CogTiff.Directory();
    for (TIFFField field : metadata.getTIFFFields()) directory.set(field);
    for (int tag :
        new int[] {
          CogTiff.TAG_STRIP_OFFSETS,
          CogTiff.TAG_STRIP_BYTE_COUNTS,
          CogTiff.TAG_ROWS_PER_STRIP,
          CogTiff.TAG_TILE_OFFSETS,
          CogTiff.TAG_TILE_BYTE_COUNTS
        }) directory.remove(tag);
    directory.set(dimension(CogTiff.TAG_IMAGE_WIDTH, bounds.width));
    directory.set(dimension(CogTiff.TAG_IMAGE_LENGTH, bounds.height));
    directory.set(shorts(CogTiff.TAG_COMPRESSION, codec.tag()));
    directory.set(shorts(CogTiff.TAG_PLANAR_CONFIGURATION, 1));
    directory.set(shorts(CogTiff.TAG_TILE_WIDTH, block));
    directory.set(shorts(CogTiff.TAG_TILE_LENGTH, block));
    int[] bits = new int[bands], formats = new int[bands];
    for (int band = 0; band < bands; band++) {
      bits[band] = CogTile.bitsPerSample(dataType);
      formats[band] = CogTile.sampleFormat(dataType);
    }
    directory.set(shorts(CogTiff.TAG_BITS_PER_SAMPLE, bits));
    directory.set(shorts(CogTiff.TAG_SAMPLE_FORMAT, formats));
    directory.set(shorts(CogTiff.TAG_SAMPLES_PER_PIXEL, bands));
    return directory;
  }

  private static void patchOverview(CogTiff.Directory directory, int width, int height, int block) {
    directory.set(dimension(CogTiff.TAG_IMAGE_WIDTH, width));
    directory.set(dimension(CogTiff.TAG_IMAGE_LENGTH, height));
    directory.set(shorts(CogTiff.TAG_NEW_SUBFILE_TYPE, 1));
    directory.set(shorts(CogTiff.TAG_TILE_WIDTH, block));
    directory.set(shorts(CogTiff.TAG_TILE_LENGTH, block));
    for (int tag :
        new int[] {
          CogTiff.TAG_MODEL_PIXEL_SCALE,
          CogTiff.TAG_MODEL_TIEPOINT,
          CogTiff.TAG_MODEL_TRANSFORMATION,
          CogTiff.TAG_GEO_KEY_DIRECTORY,
          CogTiff.TAG_GEO_DOUBLE_PARAMS,
          CogTiff.TAG_GEO_ASCII_PARAMS
        }) directory.remove(tag);
  }

  /** Width/height use SHORT when possible and LONG beyond 65535, matching common writers. */
  private static TIFFField dimension(int tag, long value) {
    if (value <= 65535) return shorts(tag, (int) value);
    return new TIFFField(
        new TIFFTag("Tag" + tag, tag, 1 << TIFFTag.TIFF_LONG),
        TIFFTag.TIFF_LONG,
        1,
        new long[] {value});
  }

  private static TIFFField shorts(int tag, int... values) {
    char[] data = new char[values.length];
    for (int i = 0; i < values.length; i++) data[i] = (char) values[i];
    return new TIFFField(
        new TIFFTag("Tag" + tag, tag, 1 << TIFFTag.TIFF_SHORT),
        TIFFTag.TIFF_SHORT,
        values.length,
        data);
  }

  private static List<int[]> planLevels(int width, int height, int block) {
    List<int[]> levels = new ArrayList<>();
    int w = width, h = height;
    while (w > block || h > block) {
      w = Math.max(1, (w + 1) / 2);
      h = Math.max(1, (h + 1) / 2);
      levels.add(new int[] {w, h});
    }
    return levels;
  }

  private static long tileCount(int width, int height, int block) {
    return (long) ((width + block - 1) / block) * ((height + block - 1) / block);
  }

  private static void checkStopped(BooleanSupplier stopped) throws IOException {
    if (stopped != null && stopped.getAsBoolean())
      throw new IOException("Raster write stopped");
  }

  /** Monotonic progress over overview generation (0..40%) and assembly (40..100%). */
  private static final class Work {
    private final IntConsumer progress;
    private final long overviewTotal;
    private final long assemblyTotal;
    private long overviewDone;
    private long assemblyDone;
    private int reported = -1;

    Work(IntConsumer progress, long overviewTotal, long assemblyTotal) {
      this.progress = progress;
      this.overviewTotal = overviewTotal;
      this.assemblyTotal = assemblyTotal;
    }

    void overview() {
      overviewDone++;
      int value = (int) Math.min(40, 40 * overviewDone / Math.max(1, overviewTotal));
      report(value);
    }

    void assembled() {
      assemblyDone++;
      int value =
          (int) Math.min(100, 40 + 60 * assemblyDone / Math.max(1, assemblyTotal));
      report(value);
    }

    private void report(int value) {
      if (progress == null || value <= reported) return;
      reported = value;
      progress.accept(value);
    }
  }
}
