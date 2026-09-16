package ch.so.agi.hop.raster;

public sealed interface RasterOperation permits RasterOperation.Clip, RasterOperation.Reproject {
  String origin();

  record Clip(
      String origin,
      String polygonWkt,
      boolean polygonMask,
      java.util.List<Integer> bands,
      Double noData)
      implements RasterOperation {
    public Clip {
      java.util.Objects.requireNonNull(origin);
      java.util.Objects.requireNonNull(polygonWkt);
      bands = java.util.List.copyOf(bands);
      if (bands.isEmpty()
          || bands.stream().anyMatch(b -> b < 0)
          || new java.util.HashSet<>(bands).size() != bands.size())
        throw new IllegalArgumentException("Invalid band selection");
    }
  }

  record Reproject(
      String origin,
      String targetCrs,
      double resolutionX,
      double resolutionY,
      java.util.List<Double> extent,
      String interpolation,
      String outputType,
      Double sourceNoData,
      Double outputNoData)
      implements RasterOperation {
    public Reproject {
      java.util.Objects.requireNonNull(origin);
      extent = java.util.List.copyOf(extent);
      if (!(resolutionX > 0)
          || !(resolutionY > 0)
          || !Double.isFinite(resolutionX)
          || !Double.isFinite(resolutionY)
          || !(extent.isEmpty() || extent.size() == 4))
        throw new IllegalArgumentException("Invalid target grid");
      if (!java.util.Set.of("NEAREST", "BILINEAR").contains(interpolation)
          || !java.util.Set.of("AUTO", "SOURCE", "FLOAT32", "FLOAT64").contains(outputType))
        throw new IllegalArgumentException("Invalid resampling");
    }
  }
}
