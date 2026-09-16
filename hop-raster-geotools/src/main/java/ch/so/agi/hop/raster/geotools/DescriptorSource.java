package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.RasterDescriptor;
import java.awt.Rectangle;
import java.awt.image.Raster;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.referencing.operation.transform.AffineTransform2D;

/** Metadata-only planning view: creating an operation cannot open or read the source. */
public final class DescriptorSource implements RasterSource {
  private final RasterDescriptor d;
  private final CoordinateReferenceSystem crs;

  public DescriptorSource(RasterDescriptor d) throws Exception {
    this.d = d;
    GeoToolsRuntimeSupport.initialize();
    crs = d.crsWkt() == null ? null : CrsMetadata.parse(d.crsWkt());
  }

  public Rectangle bounds() {
    return new Rectangle(d.grid().width(), d.grid().height());
  }

  public CoordinateReferenceSystem crs() {
    return crs;
  }

  public MathTransform gridToWorld() {
    var g = d.grid();
    return new AffineTransform2D(g.m00(), g.m10(), g.m01(), g.m11(), g.m02(), g.m12());
  }

  public int bands() {
    return d.bands().size();
  }

  public int dataType() {
    return d.bands().getFirst().dataType();
  }

  public Double noData(int band) {
    return d.bands().get(band).noData();
  }

  public double scale(int band) {
    return d.bands().get(band).scale();
  }

  public double offset(int band) {
    return d.bands().get(band).offset();
  }

  public boolean valid(double value, int band) {
    return Double.isFinite(value) && (noData(band) == null || value != noData(band));
  }

  public double physical(double value, int band) {
    return value * scale(band) + offset(band);
  }

  public RasterColorInfo colorInfo() {
    char[] map = d.palette().isEmpty() ? null : new char[d.palette().size()];
    if (map != null)
      for (int i = 0; i < map.length; i++) map[i] = (char) d.palette().get(i).intValue();
    return new RasterColorInfo(
        RasterColorInfo.Kind.valueOf(d.color()), d.alphaBand(), d.associatedAlpha(), map);
  }

  public Raster read(RasterReadRequest request) {
    throw new IllegalStateException("Metadata planning cannot read pixels");
  }

  public void close() {}
}
