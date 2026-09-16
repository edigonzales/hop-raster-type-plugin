package ch.so.agi.hop.raster.geotools;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageWriteParam;
import org.eclipse.imagen.*;
import org.geotools.api.referencing.datum.PixelInCell;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.*;
import org.geotools.coverage.processing.CoverageProcessor;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.util.factory.Hints;

/** Bounded, lazy output tiles backed by GeoTools Resample operations on weighted source windows. */
public final class RasterReproject {
  private RasterReproject() {}

  public static void write(
      RasterSource source, RasterReprojectRequest request, BooleanSupplier stopped)
      throws Exception {
    checkStopped(stopped);
    if (source instanceof GeoTiffSource tiff) tiff.requireDifferentOutput(request.output());
    var grid = RasterTargetGrid.create(source, request);
    var format = Format.create(source, request);
    Path output = request.output().toAbsolutePath().normalize();
    if (!Files.isDirectory(output.getParent()))
      throw new IOException("Output directory does not exist");
    if (!request.overwrite() && Files.exists(output))
      throw new IOException("Output already exists: " + output);
    Path temp = Files.createTempFile(output.getParent(), ".hop-raster-", ".tif");
    ReprojectImage image = null;
    try {
      image = new ReprojectImage(source, request, grid, format, stopped);
      var options = new GeoTiffWriteParams();
      options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      options.setCompressionType("Deflate");
      options.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
      options.setTiling(512, 512);
      double bytes =
          (double) grid.width()
              * grid.height()
              * format.bands
              * DataBuffer.getDataTypeSize(format.type)
              / 8;
      options.setForceToBigTIFF(bytes >= 2L * 1024 * 1024 * 1024);
      double[] scales = new double[format.bands], offsets = new double[format.bands];
      Arrays.fill(scales, 1);
      if (format.color.kind() == RasterColorInfo.Kind.NUMERIC)
        for (int b = 0; b < format.bands; b++) {
          scales[b] = source.scale(b);
          offsets[b] = source.offset(b);
        }
      GeoTiffOutput.write(
          temp,
          image,
          grid.crs(),
          grid.gridToWorld(),
          scales,
          offsets,
          format.noData,
          format.color,
          options);
      checkStopped(stopped);
      if (request.overwrite()) Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
      else Files.move(temp, output);
    } finally {
      if (image != null) image.dispose();
      Files.deleteIfExists(temp);
    }
  }

  private static void checkStopped(BooleanSupplier stopped) throws IOException {
    GeoTiffSource.checkInterrupted();
    if (stopped.getAsBoolean()) throw new IOException("Raster reprojection stopped");
  }

  record Format(int type, int bands, RasterColorInfo color, Double noData) {
    static Format create(RasterSource source, RasterReprojectRequest request) {
      RasterColorInfo info = source.colorInfo();
      if (info == null || info.kind() == RasterColorInfo.Kind.UNSUPPORTED)
        throw new IllegalArgumentException("Unsupported TIFF color interpretation");
      boolean palette = info.kind() == RasterColorInfo.Kind.PALETTE;
      boolean preservePalette =
          palette && request.interpolation() == RasterReprojectRequest.Interpolation.NEAREST;
      if (palette
          && (info.colorMap() == null
              || info.colorMap().length % 3 != 0
              || (source.dataType() != DataBuffer.TYPE_BYTE
                  && source.dataType() != DataBuffer.TYPE_USHORT)))
        throw new IllegalArgumentException("Unsupported TIFF palette");
      if (info.kind() == RasterColorInfo.Kind.NUMERIC || preservePalette) {
        int type =
            preservePalette
                ? source.dataType()
                : switch (request.outputType()) {
                  case AUTO ->
                      request.interpolation() == RasterReprojectRequest.Interpolation.NEAREST
                          ? source.dataType()
                          : DataBuffer.TYPE_DOUBLE;
                  case SOURCE -> source.dataType();
                  case FLOAT32 -> DataBuffer.TYPE_FLOAT;
                  case FLOAT64 -> DataBuffer.TYPE_DOUBLE;
                };
        Double nd =
            request.outputNoData() != null
                ? request.outputNoData()
                : request.sourceNoData() != null ? request.sourceNoData() : source.noData(0);
        if (nd == null && !preservePalette) {
          if (type == DataBuffer.TYPE_FLOAT || type == DataBuffer.TYPE_DOUBLE) nd = Double.NaN;
          else
            throw new IllegalArgumentException("Integer output requires an explicit NoData value");
        }
        if (nd != null) {
          RasterSampleValues.validateNoData(nd, type);
          if (preservePalette && (nd < 0 || nd >= info.colorMap().length / 3))
            throw new IllegalArgumentException(
                "Palette NoData must name an existing palette entry");
        }
        return new Format(type, source.bands(), info, nd);
      }
      int type = palette ? DataBuffer.TYPE_USHORT : source.dataType();
      if (type != DataBuffer.TYPE_BYTE
          && type != DataBuffer.TYPE_USHORT
          && type != DataBuffer.TYPE_FLOAT
          && type != DataBuffer.TYPE_DOUBLE)
        throw new IllegalArgumentException(
            "Color samples must be unsigned 8/16 bit or floating point");
      boolean gray = info.kind() == RasterColorInfo.Kind.GRAY_ALPHA;
      int bands = gray ? 2 : 4;
      return new Format(
          type,
          bands,
          new RasterColorInfo(
              gray ? RasterColorInfo.Kind.GRAY_ALPHA : RasterColorInfo.Kind.RGB,
              bands - 1,
              false,
              null),
          null);
    }
  }

  static final class ReprojectImage extends SourcelessOpImage {
    // At most 8 MiB for a four-plane Float64 source block. Output is evaluated one tile at a time.
    private static final long MAX_SOURCE_PIXELS = 256L * 1024;
    private final RasterSource source;
    private final Rectangle sourceBounds;
    private final RasterReprojectRequest request;
    private final RasterTargetGrid grid;
    private final Format format;
    private final BooleanSupplier stopped;
    private final MathTransform worldToSource;
    private final TileCache operationCache;
    private final CoverageProcessor processor;

    ReprojectImage(
        RasterSource source,
        RasterReprojectRequest request,
        RasterTargetGrid grid,
        Format format,
        BooleanSupplier stopped)
        throws Exception {
      this(
          source,
          request,
          grid,
          format,
          stopped,
          ImageN.createTileCache(16L * 1024 * 1024),
          ImageN.createTileCache(8L * 1024 * 1024));
    }

    ReprojectImage(
        RasterSource source,
        RasterReprojectRequest request,
        RasterTargetGrid grid,
        Format format,
        BooleanSupplier stopped,
        TileCache outputCache,
        TileCache operationCache)
        throws Exception {
      super(
          new ImageLayout()
              .setTileWidth(512)
              .setTileHeight(512)
              .setColorModel(GeoTiffOutput.colorModel(format.type, format.bands, format.color)),
          new RenderingHints(ImageN.KEY_TILE_CACHE, outputCache),
          new BandedSampleModel(format.type, 512, 512, format.bands),
          0,
          0,
          grid.width(),
          grid.height());
      this.operationCache = operationCache;
      this.source = source;
      this.sourceBounds = source.bounds();
      this.request = request;
      this.grid = grid;
      this.format = format;
      this.stopped = stopped;
      worldToSource = source.gridToWorld().inverse();
      var hints = new Hints(ImageN.KEY_TILE_CACHE, operationCache);
      hints.put(
          ImageN.KEY_BORDER_EXTENDER, BorderExtender.createInstance(BorderExtender.BORDER_ZERO));
      hints.put(Hints.LENIENT_DATUM_SHIFT, false);
      processor = new CoverageProcessor(hints);
    }

    @Override
    protected synchronized void computeRect(
        PlanarImage[] ignored, WritableRaster destination, Rectangle rect) {
      try {
        process(destination, rect);
      } catch (Exception e) {
        throw new IllegalStateException("Unable to reproject raster tile: " + e.getMessage(), e);
      }
    }

    private double[] coordinates(Rectangle rect) throws Exception {
      double[] xy = new double[rect.width * rect.height * 2];
      int i = 0;
      for (int y = rect.y; y < rect.y + rect.height; y++)
        for (int x = rect.x; x < rect.x + rect.width; x++) {
          xy[i++] = x;
          xy[i++] = y;
        }
      grid.gridToWorld().transform(xy, 0, xy, 0, xy.length / 2);
      grid.targetToSource().transform(xy, 0, xy, 0, xy.length / 2);
      worldToSource.transform(xy, 0, xy, 0, xy.length / 2);
      for (double v : xy)
        if (!Double.isFinite(v))
          throw new IllegalArgumentException("Target pixel cannot be transformed to source CRS");
      return xy;
    }

    private boolean inside(double x, double y) {
      Rectangle b = sourceBounds;
      return x >= b.x - .5 && y >= b.y - .5 && x < b.getMaxX() - .5 && y < b.getMaxY() - .5;
    }

    private Rectangle sourceWindow(double[] xy) {
      double minX = Double.POSITIVE_INFINITY,
          minY = minX,
          maxX = Double.NEGATIVE_INFINITY,
          maxY = maxX;
      for (int i = 0; i < xy.length; i += 2)
        if (inside(xy[i], xy[i + 1])) {
          minX = Math.min(minX, xy[i]);
          maxX = Math.max(maxX, xy[i]);
          minY = Math.min(minY, xy[i + 1]);
          maxY = Math.max(maxY, xy[i + 1]);
        }
      if (!Double.isFinite(minX)) return new Rectangle();
      // A two-pixel halo also isolates ImageN's source-border behavior from valid neighbours.
      int x = (int) Math.floor(minX) - 2, y = (int) Math.floor(minY) - 2;
      return new Rectangle(x, y, (int) Math.ceil(maxX) - x + 3, (int) Math.ceil(maxY) - y + 3);
    }

    private void process(WritableRaster destination, Rectangle rect) throws Exception {
      checkStopped(stopped);
      double[] xy = coordinates(rect);
      Rectangle window = sourceWindow(xy);
      if ((long) window.width * window.height > MAX_SOURCE_PIXELS) {
        // Split the target, not a giant materialized source region. Even one heavily downsampled
        // target pixel needs only its own nearest/bilinear neighbourhood.
        if (rect.width >= rect.height && rect.width > 1) {
          int n = rect.width / 2;
          process(destination, new Rectangle(rect.x, rect.y, n, rect.height));
          process(destination, new Rectangle(rect.x + n, rect.y, rect.width - n, rect.height));
        } else if (rect.height > 1) {
          int n = rect.height / 2;
          process(destination, new Rectangle(rect.x, rect.y, rect.width, n));
          process(destination, new Rectangle(rect.x, rect.y + n, rect.width, rect.height - n));
        } else throw new IllegalArgumentException("Source neighbourhood exceeds read limit");
        return;
      }
      boolean numeric =
          format.color.kind() == RasterColorInfo.Kind.NUMERIC
              || format.color.kind() == RasterColorInfo.Kind.PALETTE;
      if (window.isEmpty()) {
        for (int y = rect.y; y < rect.y + rect.height; y++)
          for (int x = rect.x; x < rect.x + rect.width; x++)
            for (int b = 0; b < format.bands; b++)
              destination.setSample(x, y, b, numeric ? missing() : 0);
        return;
      }
      Rectangle readWindow = window.intersection(source.bounds());
      if (numeric) numeric(destination, rect, window, readWindow, xy);
      else colors(destination, rect, window, readWindow, xy);
    }

    // Separate pixel loops limit transient JIT compiler memory in long operation chains.
    // Each stage still resamples and rounds independently.
    private void numeric(
        WritableRaster destination,
        Rectangle rect,
        Rectangle window,
        Rectangle readWindow,
        double[] xy)
        throws Exception {
      for (int band = 0; band < format.bands; band++) {
        checkStopped(stopped);
        Raster input = source.read(new RasterReadRequest(readWindow, band));
        WritableRaster weighted = raster(window, 2);
        populateWeighted(input, weighted, readWindow, band);
        Raster result = resample(weighted, rect);
        storeNumeric(destination, result, rect, xy, band);
      }
    }

    private void populateWeighted(
        Raster input, WritableRaster weighted, Rectangle readWindow, int band) {
      for (int y = readWindow.y; y < readWindow.y + readWindow.height; y++)
        for (int x = readWindow.x; x < readWindow.x + readWindow.width; x++) {
          double value = input.getSampleDouble(x, y, 0);
          if (valid(value, band)) {
            weighted.setSample(x, y, 0, value);
            weighted.setSample(x, y, 1, 1);
          }
        }
    }

    private void storeNumeric(
        WritableRaster destination, Raster result, Rectangle rect, double[] xy, int band) {
      int i = 0;
      for (int y = rect.y; y < rect.y + rect.height; y++)
        for (int x = rect.x; x < rect.x + rect.width; x++, i += 2) {
          double weight = result.getSampleDouble(x, y, 1);
          double value = missingIfNeeded(weight <= 0 || !inside(xy[i], xy[i + 1]));
          if (weight > 0 && inside(xy[i], xy[i + 1])) {
            value =
                RasterSampleValues.convert(result.getSampleDouble(x, y, 0) / weight, format.type);
            if (format.noData != null && value == format.noData)
              throw new IllegalArgumentException("Valid output sample collides with output NoData");
          }
          destination.setSample(x, y, band, value);
        }
    }

    private double missingIfNeeded(boolean missing) {
      return missing ? missing() : 0;
    }

    private double missing() {
      if (format.noData == null)
        throw new IllegalArgumentException(
            "Palette output needs an unused NoData index for transparent areas");
      return format.noData;
    }

    private boolean valid(double value, int band) {
      return ZonalStatistics.valid(source, value, band, request.sourceNoData());
    }

    private void colors(
        WritableRaster destination,
        Rectangle rect,
        Rectangle window,
        Rectangle readWindow,
        double[] xy)
        throws Exception {
      RasterColorInfo info = source.colorInfo();
      int channels = format.bands - 1;
      WritableRaster weighted = raster(window, format.bands);
      Raster alpha =
          info.alphaBand() < 0
              ? null
              : source.read(new RasterReadRequest(readWindow, info.alphaBand()));
      double sourceMax = channelMax(source.dataType());
      boolean palette = info.kind() == RasterColorInfo.Kind.PALETTE;
      char[] map = info.colorMap();
      Raster[] input = new Raster[palette ? 1 : channels];
      for (int b = 0; b < input.length; b++)
        input[b] = source.read(new RasterReadRequest(readWindow, b));
      for (int y = readWindow.y; y < readWindow.y + readWindow.height; y++) {
        checkStopped(stopped);
        for (int x = readWindow.x; x < readWindow.x + readWindow.width; x++) {
          double a = alpha == null ? 1 : alpha.getSampleDouble(x, y, 0) / sourceMax;
          if (!Double.isFinite(a)) a = 0;
          if (a < 0 || a > 1) throw new IllegalArgumentException("Alpha outside its sample range");
          boolean allNoData = true, nonfinite = false;
          for (int b = 0; b < input.length; b++) {
            double v = input[b].getSampleDouble(x, y, 0);
            nonfinite |= !Double.isFinite(v);
            allNoData &= !valid(v, b);
          }
          if (allNoData || nonfinite) a = 0;
          if (a == 0) continue;
          for (int b = 0; b < channels; b++) {
            double v;
            if (palette) {
              int index = (int) input[0].getSampleDouble(x, y, 0);
              if (index < 0 || index >= map.length / 3)
                throw new IllegalArgumentException("Sample outside palette");
              v = map[b * (map.length / 3) + index];
            } else v = input[b].getSampleDouble(x, y, 0);
            double limit = palette ? 65535 : sourceMax;
            if (v < 0 || v > limit)
              throw new IllegalArgumentException("Color sample outside its sample range");
            weighted.setSample(x, y, b, info.associatedAlpha() ? v : v * a);
          }
          weighted.setSample(x, y, channels, a);
        }
      }
      Raster result = resample(weighted, rect);
      int i = 0;
      for (int y = rect.y; y < rect.y + rect.height; y++)
        for (int x = rect.x; x < rect.x + rect.width; x++, i += 2) {
          double a = inside(xy[i], xy[i + 1]) ? result.getSampleDouble(x, y, channels) : 0;
          for (int b = 0; b < channels; b++)
            destination.setSample(
                x,
                y,
                b,
                a <= 0
                    ? 0
                    : RasterSampleValues.convert(result.getSampleDouble(x, y, b) / a, format.type));
          destination.setSample(
              x,
              y,
              channels,
              RasterSampleValues.convert(
                  Math.max(0, Math.min(1, a)) * channelMax(format.type), format.type));
        }
    }

    private static double channelMax(int type) {
      return type == DataBuffer.TYPE_BYTE ? 255 : type == DataBuffer.TYPE_USHORT ? 65535 : 1;
    }

    private static WritableRaster raster(Rectangle rect, int bands) {
      var model = new BandedSampleModel(DataBuffer.TYPE_DOUBLE, rect.width, rect.height, bands);
      return Raster.createWritableRaster(
          model, model.createDataBuffer(), new Point(rect.x, rect.y));
    }

    private Raster resample(WritableRaster weighted, Rectangle target) throws Exception {
      checkStopped(stopped);
      GridCoverage2D input = null, output = null;
      try {
        var local = weighted.createWritableTranslatedChild(0, 0);
        var image =
            new BufferedImage(
                GeoTiffOutput.colorModel(
                    DataBuffer.TYPE_DOUBLE, weighted.getNumBands(), RasterColorInfo.numeric()),
                local,
                false,
                null);
        var shift =
            new org.geotools.referencing.operation.transform.AffineTransform2D(
                1, 0, 0, 1, weighted.getMinX(), weighted.getMinY());
        var transform =
            org.geotools.referencing.operation.transform.ConcatenatedTransform.create(
                shift, source.gridToWorld());
        input =
            new GridCoverageFactory()
                .create("weighted", image, source.crs(), transform, null, null, null);
        var parameters = processor.getOperation("Resample").getParameters();
        parameters.parameter("Source").setValue(input);
        parameters.parameter("CoordinateReferenceSystem").setValue(grid.crs());
        parameters
            .parameter("GridGeometry")
            .setValue(
                new GridGeometry2D(
                    new GridEnvelope2D(target),
                    PixelInCell.CELL_CENTER,
                    grid.gridToWorld(),
                    grid.crs(),
                    null));
        parameters
            .parameter("InterpolationType")
            .setValue(
                request.interpolation() == RasterReprojectRequest.Interpolation.NEAREST
                    ? "NearestNeighbor"
                    : "Bilinear");
        parameters.parameter("BackgroundValues").setValue(new double[weighted.getNumBands()]);
        output = (GridCoverage2D) processor.doOperation(parameters);
        // Only the bounded target block is materialized, before disposing its source coverage.
        return output.getRenderedImage().getData(target);
      } finally {
        if (output != null && output != input) output.dispose(true);
        if (input != null) input.dispose(true);
        operationCache.flush();
      }
    }

    @Override
    public void dispose() {
      operationCache.flush();
      getTileCache().flush();
      super.dispose();
    }
  }
}
