package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.*;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Large backing images, bounded polygon masks and exact metadata checks. */
public final class MultiBandProbe {
  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    for (String kind : List.of("rgba", "palette", "numeric4"))
      try (var backend = new GeoToolsRasterBackend()) {
        var value = backend.describe(dir.resolve(kind + ".tif").toString());
        var g = value.result().grid();
        var outer = AcceptanceProbe.region(g, 0, 0, 1024);
        var inner = AcceptanceProbe.region(g, 256, 256, 256);
        var factory = new GeometryFactory();
        var mask =
            factory.createPolygon(
                (LinearRing) outer.getExteriorRing(),
                new LinearRing[] {(LinearRing) inner.getExteriorRing()});
        var bands =
            kind.equals("numeric4")
                ? List.of(2, 0)
                : java.util.stream.IntStream.range(0, value.result().bands().size())
                    .boxed()
                    .toList();
        var derived =
            backend.derive(
                value, new RasterOperation.Clip("hole", mask.toText(), true, bands, null));
        try (var session = backend.session(derived, () -> false)) {
          var band = kind.equals("rgba") ? 3 : 0;
          double hole = session.read(new RasterWindow(300, 300, 1, 1), band).samples().get(0);
          double expected = kind.equals("rgba") ? 0 : kind.equals("palette") ? 255 : -9999;
          if (hole != expected) throw new AssertionError("Hole mask: " + kind + " " + hole);
          if (derived.result().bands().size() != bands.size())
            throw new AssertionError("Band count");
          if (kind.equals("palette")
              && !derived.result().palette().equals(value.result().palette()))
            throw new AssertionError("Palette changed");
          if (kind.equals("numeric4") && !derived.result().color().equals("NUMERIC"))
            throw new AssertionError("Subset color");
          try (var original = backend.session(value, () -> false)) {
            for (int b = 0; b < bands.size(); b++) {
              double actual = session.read(new RasterWindow(10, 10, 1, 1), b).samples().get(0);
              double source =
                  original.read(new RasterWindow(10, 10, 1, 1), bands.get(b)).samples().get(0);
              if (actual != source) throw new AssertionError("Band order/value mismatch");
            }
          }
          AcceptanceProbe.resources(backend, 1);
        }
        backend.write(derived, dir.resolve(kind + "-masked.tif"), true, () -> false);
        AcceptanceProbe.resources(backend, 0);
        System.out.println("MULTIBAND_PASS " + kind);
      }
  }
}
