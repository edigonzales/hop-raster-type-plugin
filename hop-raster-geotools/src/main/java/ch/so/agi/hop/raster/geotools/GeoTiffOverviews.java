package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.RasterWriteOptions;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.eclipse.imagen.*;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.gce.geotiff.GeoTiffWriteParams;

/** Internal GeoTIFF overviews using the same lossless cascade as COG. */
final class GeoTiffOverviews {
  private GeoTiffOverviews() {}

  static void write(
      RasterSource source,
      RasterWriteOptions request,
      BooleanSupplier stopped,
      Path target,
      RenderedImage image,
      CoordinateReferenceSystem crs,
      AffineTransform gridToWorld,
      double[] scales,
      double[] offsets,
      Double noData,
      RasterColorInfo color,
      GeoTiffWriteParams params,
      IntConsumer progress)
      throws Exception {
    List<int[]> dimensions =
        request.overviews() == RasterWriteOptions.Overviews.AUTO
            ? OverviewPyramid.planLevels(
                source.bounds().width, source.bounds().height, request.blockSize())
            : List.of();
    if (dimensions.isEmpty()) {
      GeoTiffOutput.write(
          target,
          image,
          crs,
          gridToWorld,
          scales,
          offsets,
          noData,
          color,
          params,
          progress,
          List.of(),
          stopped);
      return;
    }
    List<OverviewPyramid.Level> levels = new ArrayList<>();
    List<OverviewImage> images = new ArrayList<>();
    int bands = source.bands(), type = source.dataType(), block = request.blockSize();
    int[] bits = new int[bands], formats = new int[bands];
    Double[] noDatas = new Double[bands];
    for (int b = 0; b < bands; b++) {
      bits[b] = CogTile.bitsPerSample(type);
      formats[b] = CogTile.sampleFormat(type);
      noDatas[b] = source.noData(b);
    }
    long tiles = 0;
    for (int[] d : dimensions)
      tiles += (long) ((d[0] + block - 1) / block) * ((d[1] + block - 1) / block);
    final long total = Math.max(1, tiles);
    long[] done = {0};
    try (var codec = CogTileCodec.create("Deflate", bands, bits, formats)) {
      CogSamples previous = new CogSamples.Source(source);
      var resampling =
          color.kind() == RasterColorInfo.Kind.PALETTE
              ? RasterWriteOptions.Resampling.NEAREST
              : request.resampling();
      for (int[] d : dimensions) {
        var level =
            OverviewPyramid.generate(
                previous,
                d,
                resampling,
                block,
                bands,
                type,
                noDatas,
                codec,
                null,
                target.getParent(),
                stopped,
                () -> {
                  if (progress != null) progress.accept((int) (40 * ++done[0] / total));
                });
        levels.add(level);
        previous =
            new CogSamples.Store(level.lossless(), d[0], d[1], type, bands, noDatas, block, codec);
        images.add(new OverviewImage(previous, color, block, stopped));
      }
      GeoTiffOutput.write(
          target,
          image,
          crs,
          gridToWorld,
          scales,
          offsets,
          noData,
          color,
          params,
          value -> {
            if (progress != null) progress.accept(40 + 60 * value / 100);
          },
          images,
          stopped);
    } finally {
      for (var overview : images) overview.dispose();
      for (var level : levels) level.close();
    }
  }

  /** No tile cache: ImageIO requests one tile at a time from the compressed store. */
  private static final class OverviewImage extends SourcelessOpImage {
    private final CogSamples samples;
    private final BooleanSupplier stopped;

    OverviewImage(CogSamples samples, RasterColorInfo color, int block, BooleanSupplier stopped) {
      super(
          new ImageLayout()
              .setTileWidth(block)
              .setTileHeight(block)
              .setColorModel(GeoTiffOutput.colorModel(samples.dataType(), samples.bands(), color)),
          null,
          new BandedSampleModel(samples.dataType(), block, block, samples.bands()),
          0,
          0,
          samples.width(),
          samples.height());
      setTileCache(null);
      this.samples = samples;
      this.stopped = stopped;
    }

    @Override
    protected void computeRect(PlanarImage[] ignored, WritableRaster destination, Rectangle rect) {
      try {
        OverviewPyramid.checkStopped(stopped);
        // The store and codec reuse buffers, so reads must be serialized.
        synchronized (samples) {
          double[][] values = samples.read(rect.x, rect.y, rect.width, rect.height);
          for (int band = 0; band < samples.bands(); band++)
            destination.setSamples(rect.x, rect.y, rect.width, rect.height, band, values[band]);
        }
      } catch (Exception e) {
        throw new IllegalStateException("Cannot write overview tile: " + e.getMessage(), e);
      }
    }
  }
}
