package ch.so.agi.hop.raster.geotools;

import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BandedSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageWriteParam;
import org.eclipse.imagen.ImageLayout;
import org.eclipse.imagen.ImageN;
import org.eclipse.imagen.PlanarImage;
import org.eclipse.imagen.SourcelessOpImage;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.locationtech.jts.geom.Geometry;

public final class RasterClip {
  private RasterClip() {}

  public static void write(
      RasterSource source,
      Geometry region,
      boolean polygonMask,
      int band,
      Double noDataOverride,
      Path target,
      boolean overwrite,
      BooleanSupplier stopped)
      throws Exception {
    if (band < 0 || band >= source.bands())
      throw new IllegalArgumentException("Band outside raster");
    PixelMask mask = new PixelMask(source, region);
    Rectangle window = mask.window();
    if (window.isEmpty()) throw new IllegalArgumentException("Clip does not overlap the raster");
    Double nd = noDataOverride != null ? noDataOverride : source.noData(band);
    if (nd == null) {
      if (source.dataType() != DataBuffer.TYPE_FLOAT && source.dataType() != DataBuffer.TYPE_DOUBLE)
        throw new IllegalArgumentException("Integer raster requires an explicit NoData value");
      nd = Double.NaN;
    }
    validateNoData(nd, source.dataType());
    Path output = target.toAbsolutePath().normalize();
    if (!overwrite && Files.exists(output))
      throw new IOException("Output already exists: " + output);
    if (!Files.isDirectory(output.getParent()))
      throw new IOException("Output directory does not exist");
    Path temp = Files.createTempFile(output.getParent(), ".hop-raster-", ".tif");
    ClipImage image = null;
    try {
      image = new ClipImage(source, mask, polygonMask, band, nd, noDataOverride, stopped);
      GeoTiffWriteParams params = new GeoTiffWriteParams();
      params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      params.setCompressionType("Deflate");
      params.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
      params.setTiling(512, 512);
      params.setForceToBigTIFF(
          (long) window.width * window.height * DataBuffer.getDataTypeSize(source.dataType()) / 8
              >= 2L * 1024 * 1024 * 1024);
      GeoTiffOutput.write(temp, image, source, band, nd, params);
      if (stopped.getAsBoolean()) throw new IOException("Clip stopped");
      if (overwrite) Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
      else Files.move(temp, output);
    } finally {
      if (image != null) image.dispose();
      Files.deleteIfExists(temp);
    }
  }

  private static void validateNoData(double value, int type) {
    if (type == DataBuffer.TYPE_DOUBLE) return;
    if (type == DataBuffer.TYPE_FLOAT) {
      if (Double.isFinite(value) && (double) (float) value != value)
        throw new IllegalArgumentException("NoData is not exactly representable as Float32");
      return;
    }
    double min =
        switch (type) {
          case DataBuffer.TYPE_SHORT -> Short.MIN_VALUE;
          case DataBuffer.TYPE_INT -> Integer.MIN_VALUE;
          default -> 0;
        };
    double max =
        switch (type) {
          case DataBuffer.TYPE_BYTE -> 255;
          case DataBuffer.TYPE_SHORT -> Short.MAX_VALUE;
          case DataBuffer.TYPE_USHORT -> 65535;
          default -> Integer.MAX_VALUE;
        };
    if (!Double.isFinite(value) || value != Math.rint(value) || value < min || value > max)
      throw new IllegalArgumentException("NoData is not representable in the raster data type");
  }

  private static final class ClipImage extends SourcelessOpImage {
    private final RasterSource source;
    private final PixelMask mask;
    private final boolean polygon;
    private final int band;
    private final double noData;
    private final Double override;
    private final BooleanSupplier stopped;

    ClipImage(
        RasterSource source,
        PixelMask mask,
        boolean polygon,
        int band,
        double noData,
        Double override,
        BooleanSupplier stopped) {
      super(
          new ImageLayout().setTileWidth(512).setTileHeight(512),
          new RenderingHints(ImageN.KEY_TILE_CACHE, ImageN.createTileCache(0)),
          new BandedSampleModel(source.dataType(), 512, 512, 1),
          mask.window().x,
          mask.window().y,
          mask.window().width,
          mask.window().height);
      this.source = source;
      this.mask = mask;
      this.polygon = polygon;
      this.band = band;
      this.noData = noData;
      this.override = override;
      this.stopped = stopped;
    }

    @Override
    protected void computeRect(PlanarImage[] sources, WritableRaster destination, Rectangle rect) {
      try {
        GeoTiffSource.checkInterrupted();
        if (stopped.getAsBoolean()) throw new IOException("Clip stopped");
        Raster input = source.read(new RasterReadRequest(rect, band));
        for (int y = rect.y; y < rect.y + rect.height; y++)
          for (int x = rect.x; x < rect.x + rect.width; x++) {
            double value = input.getSampleDouble(x, y, 0);
            destination.setSample(
                x,
                y,
                0,
                (!polygon || mask.covers(x, y))
                        && ZonalStatistics.valid(source, value, band, override)
                    ? value
                    : noData);
          }
      } catch (Exception e) {
        throw new IllegalStateException("Unable to read clip tile", e);
      }
    }
  }
}
