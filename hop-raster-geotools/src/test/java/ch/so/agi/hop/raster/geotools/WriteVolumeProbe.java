package ch.so.agi.hop.raster.geotools;

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
    System.out.println("WRITE_VOLUME_PASS");
  }
}
