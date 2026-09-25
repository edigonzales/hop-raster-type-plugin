package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.RasterWriteOptions;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/** Separate instrumented run: Java write bytes, never device I/O or timing evidence. */
public final class WriteVolumeProbe {
  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    Path fixtures = out.resolve("calibration");
    try (var recording = new Recording()) {
      recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO).withoutStackTrace();
      recording.start();
      BenchmarkFixtures.main(new String[] {fixtures.toString(), "1024"});
      AcceptanceProbe.main(
          new String[] {
            fixtures.resolve("dem.tif").toString(), out.resolve("chain").toString(), "10"
          });
      for (boolean cancel : new boolean[] {true, false}) {
        Path target = out.resolve("chain").resolve(cancel ? "cancelled.tif" : "failed.tif");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var backend = new GeoToolsRasterBackend()) {
          var value = backend.describe(fixtures.resolve("dem.tif").toString());
          var raw = (GeoTiffSource) AcceptanceProbe.field(backend, "source");
          boolean failed = false;
          try {
            backend.write(
                value,
                target,
                false,
                () -> {
                  if (calls.incrementAndGet() == 2) {
                    if (cancel) return true;
                    raw.close();
                  }
                  return false;
                });
          } catch (java.io.IOException expected) {
            failed = true;
          }
          if (!failed || calls.get() < 2 || Files.exists(target))
            throw new AssertionError("Failed writer retained target");
        }
        System.out.println(cancel ? "ABORT_CLEANUP_PASS" : "ERROR_CLEANUP_PASS");
      }
      recording.stop();
      recording.dump(out.resolve("writes.jfr"));
    }
    Map<String, Long> bytes = new TreeMap<>();
    try (var input = new RecordingFile(out.resolve("writes.jfr"))) {
      while (input.hasMoreEvents()) {
        var event = input.readEvent();
        if (event.getEventType().getName().equals("jdk.FileWrite")) {
          String path = event.getString("path");
          if (path != null && path.endsWith(".tif"))
            bytes.merge(path, event.getLong("bytesWritten"), Long::sum);
        }
      }
    }
    boolean calibration = false, neighbor = false, unexpected = false;
    for (var entry : bytes.entrySet()) {
      Path path = Path.of(entry.getKey()).toAbsolutePath();
      if (path.startsWith(fixtures) && entry.getValue() > 0) calibration = true;
      else if (path.getFileName().toString().startsWith(".hop-raster-")
          && path.startsWith(out.resolve("chain"))) neighbor = true;
      else unexpected = true;
      System.out.println("WRITE_BYTES " + entry.getValue() + " " + path);
    }
    try (var paths = Files.walk(out)) {
      if (paths.anyMatch(p -> p.getFileName().toString().startsWith(".hop-raster-")))
        throw new AssertionError("Temporary output remains");
    }
    if (!calibration || !neighbor || unexpected)
      throw new AssertionError("Incomplete or unexpected TIFF write coverage");

    // Cloud Optimized GeoTIFF: the compressed overview store and the destination temp must stay
    // bounded and must be removed; the write volume is compared against the published file.
    Path cog = out.resolve("chain").resolve("cog.tif");
    try (var recording = new Recording()) {
      recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO).withoutStackTrace();
      recording.start();
      try (var backend = new GeoToolsRasterBackend()) {
        var value = backend.describe(fixtures.resolve("dem.tif").toString());
        backend.write(value, cog, false, () -> false, RasterWriteOptions.cog("Deflate"), ignored -> {});
      }
      recording.stop();
      recording.dump(out.resolve("writes-cog.jfr"));
    }
    if (!Files.exists(cog)) throw new AssertionError("COG output missing");
    long cogSize = Files.size(cog);
    long temporary = temporaryWrites(out, out.resolve("writes-cog.jfr"));
    if (temporary <= 0) throw new AssertionError("COG wrote no temporary files");
    if (temporary > 2 * cogSize)
      throw new AssertionError(
          "COG temporary write volume " + temporary + " exceeds twice " + cogSize);
    System.out.println("COG_TEMPORARY_BYTES " + temporary + " OF " + cogSize);

    // JPEG COG: the lossless cascade store dominates the temporary volume for lossy output, so
    // the budget is wider than for lossless codecs.
    Path jpegCog = out.resolve("chain").resolve("cog-jpeg.tif");
    try (var recording = new Recording()) {
      recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO).withoutStackTrace();
      recording.start();
      try (var backend = new GeoToolsRasterBackend()) {
        var value = backend.describe(fixtures.resolve("rgb.tif").toString());
        backend.write(
            value,
            jpegCog,
            false,
            () -> false,
            new RasterWriteOptions(
                RasterWriteOptions.Format.COG,
                RasterWriteOptions.Overviews.AUTO,
                RasterWriteOptions.Resampling.AVERAGE,
                512,
                "JPEG",
                75),
            ignored -> {});
      }
      recording.stop();
      recording.dump(out.resolve("writes-jpeg.jfr"));
    }
    if (!Files.exists(jpegCog)) throw new AssertionError("JPEG COG output missing");
    long jpegSize = Files.size(jpegCog);
    long jpegTemporary = temporaryWrites(out, out.resolve("writes-jpeg.jfr"));
    if (jpegTemporary <= 0) throw new AssertionError("JPEG COG wrote no temporary files");
    if (jpegTemporary > 3 * jpegSize)
      throw new AssertionError(
          "JPEG COG temporary write volume " + jpegTemporary + " exceeds three times " + jpegSize);
    System.out.println("COG_JPEG_TEMPORARY_BYTES " + jpegTemporary + " OF " + jpegSize);

    try (var paths = Files.walk(out)) {
      if (paths.anyMatch(p -> p.getFileName().toString().startsWith(".hop-raster-")))
        throw new AssertionError("Temporary COG output remains");
    }
    System.out.println("WRITE_VOLUME_PASS");
  }

  private static long temporaryWrites(Path out, Path recording) throws Exception {
    long total = 0;
    try (var input = new RecordingFile(recording)) {
      while (input.hasMoreEvents()) {
        var event = input.readEvent();
        if (!event.getEventType().getName().equals("jdk.FileWrite")) continue;
        String path = event.getString("path");
        if (path == null) continue;
        Path resolved = Path.of(path).toAbsolutePath();
        if (!resolved.startsWith(out.resolve("chain"))) continue;
        if (!resolved.getFileName().toString().startsWith(".hop-raster-")) continue;
        total += event.getLong("bytesWritten");
      }
    }
    return total;
  }
}
