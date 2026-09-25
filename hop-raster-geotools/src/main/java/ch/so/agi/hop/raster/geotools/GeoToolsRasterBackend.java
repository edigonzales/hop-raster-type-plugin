package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import javax.imageio.ImageWriteParam;
import org.eclipse.imagen.*;
import org.geotools.gce.geotiff.GeoTiffWriteParams;

/** Explicit execution context. One instance per consumer transform copy. */
public final class GeoToolsRasterBackend implements RasterBackend, AutoCloseable {
  private static final List<String> KNOWN_TIFF_COMPRESSION_TYPES =
      List.of(
          "CCITT RLE",
          "CCITT T.4",
          "CCITT T.6",
          "LZW",
          "JPEG",
          "ZLib",
          "PackBits",
          "Deflate",
          "EXIF JPEG",
          "ZSTD");

  private RasterReference current;
  private GeoTiffSource source;
  private RasterDescriptor sourceDescriptor;
  private final Set<Session> sessions = Collections.newSetFromMap(new IdentityHashMap<>());

  private String identity(String location) throws Exception {
    var ref = new RasterReference(location, null);
    if (ref.remote()) return null;
    var a =
        Files.readAttributes(
            Path.of(ref.location()), java.nio.file.attribute.BasicFileAttributes.class);
    return a.size() + ":" + a.lastModifiedTime() + ":" + a.fileKey();
  }

  private GeoTiffSource source(RasterReference ref) throws Exception {
    if (!ref.remote() && !Objects.equals(ref.identity(), identity(ref.location())))
      throw new java.io.IOException("Raster source changed: " + ref.location());
    if (!ref.equals(current)) {
      close();
      source = new GeoTiffSource(new RasterDatasetRef(ref.location()));
      current = ref;
      if (ref.remote() && ref.identity() != null && !ref.identity().equals(source.httpIdentity())) {
        close();
        throw new java.io.IOException("Raster source changed: " + ref.location());
      }
      sourceDescriptor = descriptor(source);
    }
    return source;
  }

  @Override
  public RasterDataset describe(String location) throws Exception {
    var ref = new RasterReference(location, identity(location));
    if (ref.remote() && current != null && current.location().equals(ref.location())) ref = current;
    var raw = source(ref);
    if (ref.remote() && ref.identity() == null) {
      ref = new RasterReference(ref.location(), raw.httpIdentity());
      current = ref;
    }
    return new RasterDataset(ref, sourceDescriptor, List.of());
  }

  @Override
  public RasterDataset derive(RasterDataset value, RasterOperation operation) throws Exception {
    try (var planning = new DescriptorSource(value.result());
        var derived = new DerivedSource(planning, operation, () -> false)) {
      return value.append(operation, descriptor(derived));
    }
  }

  @Override
  public RasterReader open(RasterDataset value, BooleanSupplier stopped) throws Exception {
    return session(value, stopped);
  }

  public Session session(RasterDataset value, BooleanSupplier stopped) throws Exception {
    var raw = source(value.source());
    if (!sourceDescriptor.equals(value.descriptor()))
      throw new java.io.IOException("Raster metadata changed: " + value.source().location());
    var owned = new ArrayList<DerivedSource>();
    RasterSource result = raw;
    var outputCache = ImageN.createTileCache(16L * 1024 * 1024);
    var operationCache = ImageN.createTileCache(8L * 1024 * 1024);
    try {
      for (var step : value.steps()) {
        var next =
            new DerivedSource(result, step.operation(), stopped, outputCache, operationCache);
        owned.add(next);
        result = next;
        if (!equivalent(descriptor(next), step.output()))
          throw new java.io.IOException(
              "Derived raster metadata mismatch at " + step.operation().origin());
      }
      var session = new Session(result, owned, value, outputCache, operationCache);
      sessions.add(session);
      return session;
    } catch (Exception e) {
      for (var item : owned) item.close();
      throw e;
    }
  }

  public final class Session implements RasterReader {
    private final RasterSource source;
    private final List<DerivedSource> owned;
    private final RasterDataset value;
    private final TileCache outputCache;
    private final TileCache operationCache;
    private boolean closed;

    private Session(
        RasterSource source,
        List<DerivedSource> owned,
        RasterDataset value,
        TileCache outputCache,
        TileCache operationCache) {
      this.outputCache = outputCache;
      this.operationCache = operationCache;
      this.source = source;
      this.owned = owned;
      this.value = value;
    }

    public RasterSource source() {
      if (closed) throw new IllegalStateException("Raster session closed");
      return source;
    }

    public RasterDescriptor descriptor() {
      return value.result();
    }

    public RasterTile read(RasterWindow window, int band) throws Exception {
      source();
      if ((long) window.width() * window.height() > 256L * 1024)
        throw new IllegalArgumentException("Read at most 262144 pixels per window");
      var b = source.bounds();
      var r =
          source.read(
              new RasterReadRequest(
                  new Rectangle(
                      window.x() + b.x, window.y() + b.y, window.width(), window.height()),
                  band));
      if (r.getDataBuffer() instanceof DataBufferDouble buffer
          && r.getSampleModel() instanceof BandedSampleModel model
          && model.getNumBands() == 1
          && model.getScanlineStride() == window.width()
          && model.getBankIndices()[0] == 0
          && model.getBandOffsets()[0] == 0
          && buffer.getOffset() == 0
          && buffer.getSize() == window.width() * window.height()) {
        return new RasterTile(window, java.nio.DoubleBuffer.wrap(buffer.getData()));
      }
      double[] data =
          r.getSamples(
              r.getMinX(), r.getMinY(), window.width(), window.height(), 0, (double[]) null);
      return new RasterTile(window, java.nio.DoubleBuffer.wrap(data));
    }

    public void close() {
      if (!closed) {
        closed = true;
        for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).close();
        outputCache.flush();
        operationCache.flush();
        sessions.remove(this);
      }
    }
  }

  private static boolean equivalent(RasterDescriptor a, RasterDescriptor b) throws Exception {
    if (a.equals(b)) return true;
    if (!a.grid().equals(b.grid())
        || !a.bands().equals(b.bands())
        || !a.color().equals(b.color())
        || a.alphaBand() != b.alphaBand()
        || a.associatedAlpha() != b.associatedAlpha()
        || !a.palette().equals(b.palette())) return false;
    if (a.crsWkt() == null || b.crsWkt() == null) return a.crsWkt() == b.crsWkt();
    return org.geotools.referencing.CRS.equalsIgnoreMetadata(
        CrsMetadata.parse(a.crsWkt()), CrsMetadata.parse(b.crsWkt()));
  }

  static RasterDescriptor descriptor(RasterSource source) throws Exception {
    Rectangle b = source.bounds();
    if (!(source.gridToWorld() instanceof AffineTransform affine))
      throw new IllegalArgumentException("Only affine raster grids are supported");
    var a = new AffineTransform(affine);
    a.translate(b.x, b.y);
    var grid =
        new RasterGrid(
            b.width,
            b.height,
            a.getScaleX(),
            a.getShearY(),
            a.getShearX(),
            a.getScaleY(),
            a.getTranslateX(),
            a.getTranslateY());
    var bands = new ArrayList<RasterBand>();
    for (int i = 0; i < source.bands(); i++)
      bands.add(
          new RasterBand(
              "Band " + (i + 1),
              source.dataType(),
              source.noData(i),
              source.scale(i),
              source.offset(i)));
    var color = source.colorInfo();
    var palette = new ArrayList<Integer>();
    if (color.colorMap() != null) for (char c : color.colorMap()) palette.add((int) c);
    return new RasterDescriptor(
        grid,
        CrsMetadata.wkt(source.crs()),
        null,
        bands,
        color.kind().name(),
        color.alphaBand(),
        color.associatedAlpha(),
        palette);
  }

  @Override
  public void write(RasterDataset value, Path target, boolean overwrite, BooleanSupplier stopped)
      throws Exception {
    write(value, target, overwrite, stopped, "Deflate", ignored -> {});
  }

  @Override
  public void write(
      RasterDataset value,
      Path target,
      boolean overwrite,
      BooleanSupplier stopped,
      String compression,
      IntConsumer progress)
      throws Exception {
    write(value, target, overwrite, stopped, RasterWriteOptions.geoTiff(compression), progress);
  }

  @Override
  public void write(
      RasterDataset value,
      Path target,
      boolean overwrite,
      BooleanSupplier stopped,
      RasterWriteOptions writeOptions,
      IntConsumer progress)
      throws Exception {
    Path output = target.toAbsolutePath().normalize();
    if (!value.source().remote()) {
      var original = Path.of(value.source().location());
      if (output.equals(original) || (Files.exists(output) && Files.isSameFile(output, original)))
        throw new IllegalArgumentException("Raster output must differ from its source");
    }
    if (!Files.isDirectory(output.getParent()))
      throw new java.io.IOException("Output directory does not exist");
    if (!overwrite && Files.exists(output)) throw new java.io.IOException("Output already exists");
    try (var session = session(value, stopped)) {
      RasterSource src = session.source();
      Path temp = Files.createTempFile(output.getParent(), ".hop-raster-", ".tif");
      PlanarImage image = null;
      try {
        if (writeOptions.format() == RasterWriteOptions.Format.COG) {
          boolean bigTiff =
              (double) src.bounds().width
                      * src.bounds().height
                      * src.bands()
                      * DataBuffer.getDataTypeSize(src.dataType())
                      / 8
                  >= 2L * 1024 * 1024 * 1024;
          CogOutput.write(temp, src, writeOptions, bigTiff, stopped, progress);
        } else {
          image =
              src instanceof DerivedSource derived && derived.image() != null
                  ? derived.image()
                  : new SourceImage(src, stopped, session.outputCache);
          var options = new GeoTiffWriteParams();
          if ("None".equalsIgnoreCase(writeOptions.compression())) {
            options.setCompressionMode(ImageWriteParam.MODE_DISABLED);
          } else {
            options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            options.setCompressionType(writeOptions.compression());
          }
          options.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
          options.setTiling(512, 512);
          options.setForceToBigTIFF(
              (double) src.bounds().width
                      * src.bounds().height
                      * src.bands()
                      * DataBuffer.getDataTypeSize(src.dataType())
                      / 8
                  >= 2L * 1024 * 1024 * 1024);
          double[] scales = new double[src.bands()], offsets = new double[src.bands()];
          for (int b = 0; b < src.bands(); b++) {
            scales[b] = src.scale(b);
            offsets[b] = src.offset(b);
            if (!Objects.equals(src.noData(0), src.noData(b)))
              throw new IllegalArgumentException("GeoTIFF writer requires a common NoData sentinel");
          }
          GeoTiffOutput.write(
              temp,
              image,
              src.crs(),
              (AffineTransform) src.gridToWorld(),
              scales,
              offsets,
              src.noData(0),
              src.colorInfo(),
              options,
              progress == null ? ignored -> {} : progress);
        }
        if (stopped.getAsBoolean()) throw new java.io.IOException("Raster write stopped");
        if (overwrite) Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        else Files.move(temp, output);
      } catch (Exception e) {
        throw new java.io.IOException(
            "Raster source "
                + value.source().location()
                + ", operations "
                + value.steps().stream().map(step -> step.operation().origin()).toList()
                + ": "
                + e.getMessage(),
            e);
      } finally {
        if (image != null) image.dispose();
        Files.deleteIfExists(temp);
      }
    }
  }

  /** Compression names supported by the TIFF writer bundled with this backend. */
  public static List<String> compressionTypes() {
    try {
      var type = Class.forName("it.geosolutions.imageio.plugins.tiff.TIFFImageWriteParam");
      var params = type.getConstructor(java.util.Locale.class).newInstance(new Object[] {null});
      type.getMethod("setCompressionMode", int.class)
          .invoke(params, ImageWriteParam.MODE_EXPLICIT);
      return List.of((String[]) type.getMethod("getCompressionTypes").invoke(params));
    } catch (ReflectiveOperationException | LinkageError unavailableInCallerClassLoader) {
      return KNOWN_TIFF_COMPRESSION_TYPES;
    }
  }

  /** Lossless compression names supported by COG output; callers add the uncompressed mode. */
  public static List<String> cogCompressionTypes() {
    return List.of("Deflate", "ZLib", "LZW", "ZSTD", "PackBits");
  }

  private static final class SourceImage extends SourcelessOpImage {
    private final RasterSource source;
    private final BooleanSupplier stopped;

    SourceImage(RasterSource source, BooleanSupplier stopped, TileCache outputCache) {
      super(
          new ImageLayout()
              .setTileWidth(512)
              .setTileHeight(512)
              .setColorModel(
                  GeoTiffOutput.colorModel(source.dataType(), source.bands(), source.colorInfo())),
          new RenderingHints(ImageN.KEY_TILE_CACHE, outputCache),
          new BandedSampleModel(source.dataType(), 512, 512, source.bands()),
          source.bounds().x,
          source.bounds().y,
          source.bounds().width,
          source.bounds().height);
      this.source = source;
      this.stopped = stopped;
    }

    protected void computeRect(PlanarImage[] ignored, WritableRaster destination, Rectangle rect) {
      try {
        if (stopped.getAsBoolean()) throw new java.io.IOException("Raster write stopped");
        for (int b = 0; b < source.bands(); b++) {
          var input = source.read(new RasterReadRequest(rect, b));
          destination.setSamples(
              rect.x,
              rect.y,
              rect.width,
              rect.height,
              b,
              input.getSamples(rect.x, rect.y, rect.width, rect.height, 0, (double[]) null));
        }
      } catch (Exception e) {
        throw new IllegalStateException("Cannot write raster tile: " + e.getMessage(), e);
      }
    }
  }

  @Override
  public void close() {
    for (var session : List.copyOf(sessions)) session.close();
    if (source != null) source.close();
    source = null;
    current = null;
    sourceDescriptor = null;
  }
}
