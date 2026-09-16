package ch.so.agi.hop.raster.geotools;

import it.geosolutions.imageio.compression.CompressionFinder;
import it.geosolutions.imageio.core.BasicAuthURI;
import it.geosolutions.imageio.stream.input.spi.StringImageInputStreamSpi;
import it.geosolutions.imageioimpl.plugins.cog.CogImageInputStreamSpi;
import it.geosolutions.imageioimpl.plugins.cog.CogImageReaderSpi;
import it.geosolutions.imageioimpl.plugins.cog.CogSourceSPIProvider;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BandedSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferDouble;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.spi.IIORegistry;
import org.eclipse.imagen.PlanarImage;
import org.eclipse.imagen.ROI;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.datum.PixelInCell;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.DecimationPolicy;
import org.geotools.coverage.grid.io.OverviewPolicy;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.image.util.ImageUtilities;

/** Original-resolution, bounded reads. Instances belong to one transform copy. */
public final class GeoTiffSource implements RasterSource {
  // ImageIO registries are scoped to a ThreadGroup. Scan once per registry, not once per
  // row consumer/source; a weak key avoids retaining application ThreadGroups.
  private static final Map<IIORegistry, Boolean> INITIALIZED_REGISTRIES =
      new java.util.WeakHashMap<>();

  private static void initializeImageIo() {
    var registry = IIORegistry.getDefaultInstance();
    synchronized (INITIALIZED_REGISTRIES) {
      if (INITIALIZED_REGISTRIES.containsKey(registry)) return;
      registry.registerServiceProvider(new StringImageInputStreamSpi());
      javax.imageio.ImageIO.scanForPlugins();
      CompressionFinder.scanForPlugins();
      INITIALIZED_REGISTRIES.put(registry, Boolean.TRUE);
    }
  }

  private static final long CACHE_LIMIT = 64L * 1024 * 1024;
  private final RawReader reader;
  private final StrictHttpRangeReader.Session session = new StrictHttpRangeReader.Session();
  private final Map<RasterReadRequest, Raster> cache = new LinkedHashMap<>(16, .75f, true);
  private long cacheBytes;
  private boolean closed;
  private final RasterDatasetRef reference;

  private static final class RawReader extends GeoTiffReader {
    private Rectangle requestedWindow;
    private Double rawNoData;
    private RasterColorInfo colorInfo;

    @Override
    protected void collectScaleOffset(javax.imageio.metadata.IIOMetadata metadata) {
      super.collectScaleOffset(metadata);
      // Capture the metadata already read during construction. getMetadata() reopens a
      // File-oriented stream and cannot handle a CogSourceSPIProvider in GeoTools 35.1.
      var decoder =
          new org.geotools.coverage.grid.io.imageio.geotiff.GeoTiffIIOMetadataDecoder(metadata);
      rawNoData = decoder.hasNoData() ? decoder.getNoData() : null;
      try {
        var directory =
            it.geosolutions.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(metadata);
        var photo = directory.getTIFFField(262);
        var extras = directory.getTIFFField(338);
        var samples = directory.getTIFFField(277);
        int count = samples == null ? 1 : samples.getAsInt(0);
        int interpretation = photo == null ? 1 : photo.getAsInt(0);
        int alpha = -1;
        boolean associated = false, unsupported = false;
        if (extras != null) {
          for (int i = 0; i < extras.getCount(); i++) {
            int value = extras.getAsInt(i);
            if (value == 1 || value == 2) {
              if (alpha >= 0) unsupported = true;
              alpha = count - extras.getCount() + i;
              associated = value == 1;
            } else if (value != 0) unsupported = true;
          }
        }
        RasterColorInfo.Kind kind =
            switch (interpretation) {
              case 1 -> alpha < 0 ? RasterColorInfo.Kind.NUMERIC : RasterColorInfo.Kind.GRAY_ALPHA;
              case 2 -> RasterColorInfo.Kind.RGB;
              case 3 -> RasterColorInfo.Kind.PALETTE;
              // The JPEG TIFF decoder returns RGB samples for three-channel YCbCr.
              // Restrict this to the tested JPEG encoding; other color spaces stay unsupported.
              case 6 -> directory.getTIFFField(259) != null
                      && directory.getTIFFField(259).getAsInt(0) == 7 && count == 3
                  ? RasterColorInfo.Kind.RGB : RasterColorInfo.Kind.UNSUPPORTED;
              default -> RasterColorInfo.Kind.UNSUPPORTED;
            };
        if (kind == RasterColorInfo.Kind.RGB
            && (count != (alpha < 0 ? 3 : 4) || (alpha >= 0 && alpha != 3))) unsupported = true;
        if (kind == RasterColorInfo.Kind.GRAY_ALPHA && (count != 2 || alpha != 1))
          unsupported = true;
        if (kind == RasterColorInfo.Kind.PALETTE && count != 1) unsupported = true;
        var palette = directory.getTIFFField(320);
        colorInfo =
            new RasterColorInfo(
                unsupported ? RasterColorInfo.Kind.UNSUPPORTED : kind,
                alpha,
                associated,
                palette == null ? null : palette.getAsChars());
      } catch (javax.imageio.metadata.IIOInvalidTreeException e) {
        throw new IllegalArgumentException("Unable to read TIFF color interpretation", e);
      }
    }

    @Override
    protected java.awt.geom.AffineTransform getRescaledRasterToModel(
        java.awt.image.RenderedImage image) {
      java.awt.geom.AffineTransform transform =
          new java.awt.geom.AffineTransform((java.awt.geom.AffineTransform) raster2Model);
      transform.translate(requestedWindow.x - image.getMinX(), requestedWindow.y - image.getMinY());
      return transform;
    }

    @Override
    protected Integer setReadParams(
        OverviewPolicy policy,
        javax.imageio.ImageReadParam params,
        org.geotools.geometry.GeneralBounds envelope,
        Rectangle dimensions) {
      // GeoTiffReader 35.1 otherwise only chooses resolution here, not source-region cropping.
      // The request is already expressed in the original integer pixel grid.
      params.setSourceRegion(new Rectangle(requestedWindow));
      params.setSourceSubsampling(1, 1, 0, 0);
      return 0;
    }

    RawReader(Object input) throws IOException {
      super(input);
    }

    double scale(int band) {
      return scales == null || scales[band] == null ? 1 : scales[band];
    }

    double offset(int band) {
      return offsets == null || offsets[band] == null ? 0 : offsets[band];
    }
  }

  public GeoTiffSource(RasterDatasetRef ref) throws IOException {
    GeoToolsRuntimeSupport.initialize();
    ClassLoader pluginLoader = GeoTiffSource.class.getClassLoader();
    ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
    try {
      // Hop groups plugin libraries in a classloader where ImageIO's service scan does not
      // reliably register the String provider. GeoTools otherwise selects the JDK
      // RandomAccessFile provider for a local String source and fails before reading the TIFF.
      Thread.currentThread().setContextClassLoader(pluginLoader);
      initializeImageIo();
    } finally {
      Thread.currentThread().setContextClassLoader(previousLoader);
    }
    reference = ref;
    Object input;
    if (ref.remote()) {
      input =
          new CogSourceSPIProvider(
              new BasicAuthURI(ref.location(), false),
              new CogImageReaderSpi(),
              new CogImageInputStreamSpi(),
              StrictHttpRangeReader.class.getName());
    } else {
      // Keep the source as a String so GeoTools reopens a fresh ImageIO stream for each read,
      // while avoiding the File/URL overview probe in Hop's grouped plugin classloader.
      input = ref.location();
    }
    try (var scope = session.activate()) {
      Thread.currentThread().setContextClassLoader(pluginLoader);
      reader = new RawReader(input);
    } finally {
      Thread.currentThread().setContextClassLoader(previousLoader);
    }
  }

  public void requireDifferentOutput(java.nio.file.Path target) throws IOException {
    if (reference.remote()) return;
    var input = java.nio.file.Path.of(reference.location());
    var output = target.toAbsolutePath().normalize();
    if (output.equals(input)
        || (java.nio.file.Files.exists(output) && java.nio.file.Files.isSameFile(input, output)))
      throw new IllegalArgumentException("Reproject output must differ from its input");
  }

  @Override
  public Rectangle bounds() {
    var r = reader.getOriginalGridRange();
    return new Rectangle(r.getLow(0), r.getLow(1), r.getSpan(0), r.getSpan(1));
  }

  @Override
  public CoordinateReferenceSystem crs() {
    return reader.getCoordinateReferenceSystem();
  }

  @Override
  public MathTransform gridToWorld() {
    return reader.getOriginalGridToWorld(PixelInCell.CELL_CENTER);
  }

  @Override
  public int bands() {
    try {
      return reader.getImageLayout().getSampleModel(null).getNumBands();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Override
  public RasterColorInfo colorInfo() {
    return reader.colorInfo;
  }

  @Override
  public int dataType() {
    try {
      return reader.getImageLayout().getSampleModel(null).getDataType();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Override
  public Double noData(int band) {
    Double value = reader.rawNoData;
    if (value == null) return null;
    return dataType() == DataBuffer.TYPE_FLOAT ? (double) value.floatValue() : value;
  }

  @Override
  public double scale(int band) {
    return reader.scale(band);
  }

  @Override
  public double offset(int band) {
    return reader.offset(band);
  }

  @Override
  public double physical(double value, int band) {
    return value * scale(band) + offset(band);
  }

  @Override
  public boolean valid(double value, int band) {
    Double nd = noData(band);
    return Double.isFinite(value) && (nd == null || value != nd);
  }

  @Override
  public Raster read(RasterReadRequest request) throws Exception {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try (var scope = session.activate()) {
      Thread.currentThread().setContextClassLoader(GeoTiffSource.class.getClassLoader());
      return readWindow(request);
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private Raster readWindow(RasterReadRequest request) throws Exception {
    if (closed) throw new IOException("Raster source is closed");
    Rectangle window = request.window();
    if (!bounds().contains(window) || request.band() >= bands())
      throw new IllegalArgumentException("Window or band outside raster");
    if ((long) window.width * window.height * 8 > CACHE_LIMIT)
      throw new IllegalArgumentException("Read window exceeds 64 MiB; read tiles instead");
    Raster hit = cache.get(request);
    if (hit != null) return hit;
    var grid = AbstractGridFormat.READ_GRIDGEOMETRY2D.createValue();
    grid.setValue(
        new GridGeometry2D(
            new GridEnvelope2D(window), PixelInCell.CELL_CENTER, gridToWorld(), crs(), null));
    var overview = AbstractGridFormat.OVERVIEW_POLICY.createValue();
    overview.setValue(OverviewPolicy.IGNORE);
    var decimation = AbstractGridFormat.DECIMATION_POLICY.createValue();
    decimation.setValue(DecimationPolicy.DISALLOW);
    var band = AbstractGridFormat.BANDS.createValue();
    band.setValue(new int[] {request.band()});
    var rescale = AbstractGridFormat.RESCALE_PIXELS.createValue();
    rescale.setValue(false);
    reader.requestedWindow = window;
    GridCoverage2D coverage = reader.read(grid, overview, decimation, band, rescale);
    try {
      var image = coverage.getRenderedImage();
      Raster raw = image.getData(); // only this bounded window, never the source raster
      double[] origin = {raw.getMinX(), raw.getMinY()};
      coverage.getGridGeometry().getGridToCRS2D().transform(origin, 0, origin, 0, 1);
      gridToWorld().inverse().transform(origin, 0, origin, 0, 1);
      int ox = (int) Math.round(origin[0]), oy = (int) Math.round(origin[1]);
      if (!new Rectangle(ox, oy, raw.getWidth(), raw.getHeight()).contains(window))
        throw new IOException(
            "Reader returned a different grid/resolution: requested="
                + window
                + " actual="
                + new Rectangle(ox, oy, raw.getWidth(), raw.getHeight())
                + " transform="
                + coverage.getGridGeometry());
      WritableRaster result =
          Raster.createWritableRaster(
              new BandedSampleModel(DataBuffer.TYPE_DOUBLE, window.width, window.height, 1),
              new DataBufferDouble(window.width * window.height),
              new Point(window.x, window.y));
      Object roiProperty = coverage.getProperty("GC_ROI");
      ROI roi = roiProperty instanceof ROI r ? r : null;
      for (int y = window.y; y < window.y + window.height; y++) {
        checkInterrupted();
        for (int x = window.x; x < window.x + window.width; x++) {
          int ix = x - ox + raw.getMinX(), iy = y - oy + raw.getMinY();
          result.setSample(
              x,
              y,
              0,
              roi == null || roi.contains(ix, iy) ? raw.getSampleDouble(ix, iy, 0) : Double.NaN);
        }
      }
      long bytes = (long) window.width * window.height * 8;
      while (cacheBytes + bytes > CACHE_LIMIT && !cache.isEmpty()) {
        var iterator = cache.entrySet().iterator();
        Raster removed = iterator.next().getValue();
        iterator.remove();
        cacheBytes -= (long) removed.getWidth() * removed.getHeight() * 8;
      }
      cache.put(request, result);
      cacheBytes += bytes;
      return result;
    } finally {
      try {
        // ImageRead owns streams beyond the coverage; release them after copying the window.
        ImageUtilities.disposePlanarImageChain(
            PlanarImage.wrapRenderedImage(coverage.getRenderedImage()));
      } finally {
        coverage.dispose(true);
      }
    }
  }

  public static void checkInterrupted() throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new IOException("Raster operation interrupted");
  }

  public String httpIdentity() {
    return session.length + ":" + session.validator;
  }

  public long httpBytesRead() {
    return session.bytesRead();
  }

  public long httpRequests() {
    return session.requests();
  }

  public long cachedBytes() {
    return cacheBytes;
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      cache.clear();
      cacheBytes = 0;
      reader.dispose();
      session.clear();
    }
  }
}
