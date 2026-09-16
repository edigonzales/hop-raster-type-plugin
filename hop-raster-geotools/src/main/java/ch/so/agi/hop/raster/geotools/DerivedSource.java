package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.*;
import java.awt.*;
import java.awt.image.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.locationtech.jts.io.WKTReader;

/** A session-owned operation, with no dependency on the lifetime of a producing Hop transform. */
final class DerivedSource implements RasterSource {
  private final RasterSource source;
  private final RasterOperation operation;
  private final BooleanSupplier stopped;
  private final PixelMask mask;
  private final Rectangle bounds;
  private final List<Integer> selected;
  private final RasterColorInfo color;
  private final Double[] noData;
  private final RasterReproject.ReprojectImage image;
  private final RasterTargetGrid grid;
  private final RasterReproject.Format format;

  DerivedSource(RasterSource source, RasterOperation operation, BooleanSupplier stopped)
      throws Exception {
    this(
        source,
        operation,
        stopped,
        org.eclipse.imagen.ImageN.createTileCache(16L * 1024 * 1024),
        org.eclipse.imagen.ImageN.createTileCache(8L * 1024 * 1024));
  }

  DerivedSource(
      RasterSource source,
      RasterOperation operation,
      BooleanSupplier stopped,
      org.eclipse.imagen.TileCache outputCache,
      org.eclipse.imagen.TileCache operationCache)
      throws Exception {
    this.source = source;
    this.operation = operation;
    this.stopped = stopped;
    if (operation instanceof RasterOperation.Clip c) {
      var geometry = new WKTReader().read(c.polygonWkt());
      PixelMask.validate(geometry);
      mask = new PixelMask(source, geometry);
      bounds = mask.window();
      if (bounds.isEmpty()) throw new IllegalArgumentException("Clip does not overlap raster");
      selected = c.bands();
      for (int b : selected)
        if (b >= source.bands()) throw new IllegalArgumentException("Band outside raster");
      boolean all = selected.size() == source.bands();
      for (int i = 0; i < selected.size(); i++) all &= selected.get(i) == i;
      var original = all ? source.colorInfo() : RasterColorInfo.numeric();
      // RGB channels without an unassociated alpha channel still form a complete RGB image.
      // Premultiplied channels cannot drop alpha without changing their numerical meaning.
      if (!all
          && source.colorInfo().kind() == RasterColorInfo.Kind.RGB
          && !source.colorInfo().associatedAlpha()
          && selected.equals(List.of(0, 1, 2))) {
        original = new RasterColorInfo(RasterColorInfo.Kind.RGB, -1, false, null);
      }
      boolean addAlpha =
          c.polygonMask()
              && original.kind() == RasterColorInfo.Kind.RGB
              && original.alphaBand() < 0;
      color = addAlpha ? new RasterColorInfo(original.kind(), 3, false, null) : original;
      if (color.kind() == RasterColorInfo.Kind.UNSUPPORTED)
        throw new IllegalArgumentException("Unsupported color interpretation");
      noData = new Double[selected.size() + (addAlpha ? 1 : 0)];
      for (int i = 0; i < selected.size(); i++) {
        Double nd = c.noData() != null ? c.noData() : source.noData(selected.get(i));
        if (color.kind() == RasterColorInfo.Kind.NUMERIC
            || color.kind() == RasterColorInfo.Kind.PALETTE) {
          if (nd == null && (color.kind() != RasterColorInfo.Kind.PALETTE || c.polygonMask())) {
            if (source.dataType() == DataBuffer.TYPE_FLOAT
                || source.dataType() == DataBuffer.TYPE_DOUBLE) nd = Double.NaN;
            else
              throw new IllegalArgumentException("Integer/palette clip requires explicit NoData");
          }
          if (nd != null) RasterSampleValues.validateNoData(nd, source.dataType());
          if (color.kind() == RasterColorInfo.Kind.PALETTE
              && nd != null
              && (nd < 0 || nd >= color.colorMap().length / 3))
            throw new IllegalArgumentException("Palette NoData index outside palette");
          noData[i] = nd;
        } else if (color.alphaBand() < 0) {
          // A rectangular RGB crop without alpha keeps its existing NoData interpretation.
          noData[i] = nd;
        }
      }
      image = null;
      grid = null;
      format = null;
    } else {
      mask = null;
      selected = List.of();
      var r = (RasterOperation.Reproject) operation;
      var req = request(r);
      grid = RasterTargetGrid.create(source, req);
      format = RasterReproject.Format.create(source, req);
      // Metadata planning only needs the target grid and format. Creating CoverageProcessor
      // here would initialize pixel-processing infrastructure once in the producer and again
      // in the consumer, even though the producer never reads a pixel.
      image =
          source instanceof DescriptorSource
              ? null
              : new RasterReproject.ReprojectImage(
                  source, req, grid, format, stopped, outputCache, operationCache);
      bounds = new Rectangle(0, 0, grid.width(), grid.height());
      color = format.color();
      noData = new Double[format.bands()];
      java.util.Arrays.fill(noData, format.noData());
    }
  }

  static RasterReprojectRequest request(RasterOperation.Reproject r) {
    var e = r.extent();
    return new RasterReprojectRequest(
        r.targetCrs(),
        r.resolutionX(),
        r.resolutionY(),
        e.isEmpty()
            ? null
            : new RasterReprojectRequest.Extent(e.get(0), e.get(1), e.get(2), e.get(3)),
        RasterReprojectRequest.Interpolation.valueOf(r.interpolation()),
        RasterReprojectRequest.OutputType.valueOf(r.outputType()),
        r.sourceNoData(),
        r.outputNoData(),
        java.nio.file.Path.of("unused-output"),
        false);
  }

  org.eclipse.imagen.PlanarImage image() {
    return image;
  }

  public Rectangle bounds() {
    return new Rectangle(bounds);
  }

  public CoordinateReferenceSystem crs() {
    return grid == null ? source.crs() : grid.crs();
  }

  public MathTransform gridToWorld() {
    return grid == null ? source.gridToWorld() : grid.gridToWorld();
  }

  public int bands() {
    return noData.length;
  }

  public RasterColorInfo colorInfo() {
    return color;
  }

  public int dataType() {
    return format == null ? source.dataType() : format.type();
  }

  public Double noData(int b) {
    return noData[b];
  }

  public double scale(int b) {
    return format == null
        ? (b < selected.size() ? source.scale(selected.get(b)) : 1)
        : (color.kind() == RasterColorInfo.Kind.NUMERIC ? source.scale(b) : 1);
  }

  public double offset(int b) {
    return format == null
        ? (b < selected.size() ? source.offset(selected.get(b)) : 0)
        : (color.kind() == RasterColorInfo.Kind.NUMERIC ? source.offset(b) : 0);
  }

  public boolean valid(double v, int b) {
    return Double.isFinite(v) && (noData[b] == null || v != noData[b]);
  }

  public double physical(double v, int b) {
    return v * scale(b) + offset(b);
  }

  public Raster read(RasterReadRequest request) throws Exception {
    try {
      GeoTiffSource.checkInterrupted();
      if (stopped.getAsBoolean()) throw new java.io.IOException("Raster operation stopped");
      var w = request.window();
      if ((long) w.width * w.height > 256L * 1024)
        throw new IllegalArgumentException("Read at most 262144 pixels per window");
      int band = request.band();
      if (!bounds.contains(w) || band < 0 || band >= bands())
        throw new IllegalArgumentException("Window or band outside raster");
      if (image != null)
        return image
            .getData(w)
            .createChild(w.x, w.y, w.width, w.height, w.x, w.y, new int[] {band});
      var c = (RasterOperation.Clip) operation;
      Raster input =
          band < selected.size() ? source.read(new RasterReadRequest(w, selected.get(band))) : null;
      Raster[] alphaValidity = null;
      if (band == color.alphaBand()) {
        int channels = color.kind() == RasterColorInfo.Kind.RGB ? 3 : 1;
        alphaValidity = new Raster[channels];
        for (int channel = 0; channel < channels; channel++)
          alphaValidity[channel] = source.read(new RasterReadRequest(w, selected.get(channel)));
      }
      var output =
          Raster.createWritableRaster(
              new BandedSampleModel(dataType(), w.width, w.height, 1), new Point(w.x, w.y));
      double alphaMax =
          dataType() == DataBuffer.TYPE_BYTE
              ? 255
              : dataType() == DataBuffer.TYPE_USHORT ? 65535 : 1;
      for (int y = w.y; y < w.y + w.height; y++) {
        if (stopped.getAsBoolean()) throw new java.io.IOException("Raster operation stopped");
        for (int x = w.x; x < w.x + w.width; x++) {
          boolean inside = !c.polygonMask() || mask.covers(x, y);
          double value = input == null ? alphaMax : input.getSampleDouble(x, y, 0);
          if (color.kind() == RasterColorInfo.Kind.NUMERIC
              || color.kind() == RasterColorInfo.Kind.PALETTE) {
            if (!inside || !ZonalStatistics.valid(source, value, selected.get(band), c.noData()))
              value = noData[band] == null ? value : noData[band];
          } else if (!inside) value = 0;
          if (alphaValidity != null && !Double.isFinite(value)) value = 0;
          if (alphaValidity != null && (value < 0 || value > alphaMax))
            throw new IllegalArgumentException("Alpha outside its sample range");
          if (alphaValidity != null && value != 0) {
            boolean allInvalid = true, nonfinite = false;
            for (int channel = 0; channel < alphaValidity.length; channel++) {
              double sample = alphaValidity[channel].getSampleDouble(x, y, 0);
              nonfinite |= !Double.isFinite(sample);
              allInvalid &=
                  !ZonalStatistics.valid(source, sample, selected.get(channel), c.noData());
            }
            if (allInvalid || nonfinite) value = 0;
          }
          output.setSample(x, y, 0, value);
        }
      }
      return output;
    } catch (Exception e) {
      throw new java.io.IOException(
          "Raster operation '" + operation.origin() + "' failed: " + e.getMessage(), e);
    }
  }

  public void close() {
    if (image != null) image.dispose();
  }
}
