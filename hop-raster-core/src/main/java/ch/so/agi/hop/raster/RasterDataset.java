package ch.so.agi.hop.raster;

public record RasterDataset(
    RasterReference source, RasterDescriptor descriptor, java.util.List<Step> steps) {
  public record Step(RasterOperation operation, RasterDescriptor output) {
    public Step {
      java.util.Objects.requireNonNull(operation);
      java.util.Objects.requireNonNull(output);
    }
  }

  public RasterDataset {
    java.util.Objects.requireNonNull(source);
    java.util.Objects.requireNonNull(descriptor);
    steps = java.util.List.copyOf(steps);
    if (steps.size() > 128)
      throw new IllegalArgumentException("Raster operation chain exceeds 128 steps");
  }

  public RasterDescriptor result() {
    return steps.isEmpty() ? descriptor : steps.getLast().output();
  }

  public RasterDataset append(RasterOperation operation, RasterDescriptor output) {
    var next = new java.util.ArrayList<>(steps);
    next.add(new Step(operation, output));
    return new RasterDataset(source, descriptor, next);
  }

  @Override
  public String toString() {
    return "Raster["
        + result().grid().width()
        + "x"
        + result().grid().height()
        + ", "
        + result().bands().size()
        + " bands, "
        + steps.size()
        + " operations]";
  }
}
