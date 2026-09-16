package ch.so.agi.hop.raster;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.DoubleBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

class RasterTileTest {
  @Test
  void logicalSamplesStartAtZeroAndConsumersHaveIndependentPositions() {
    var storage = DoubleBuffer.wrap(new double[] {99, 10, 20, 99});
    storage.position(1).limit(3);
    var tile = new RasterTile(new RasterWindow(0, 0, 2, 1), storage);
    assertEquals(10, tile.samples().get(0));
    assertEquals(20, tile.samples().get(1));
    var first = tile.samples();
    first.get();
    assertEquals(0, tile.samples().position());
    assertThrows(ReadOnlyBufferException.class, () -> first.put(0, 0));
    assertEquals(1, storage.position());
  }
}
