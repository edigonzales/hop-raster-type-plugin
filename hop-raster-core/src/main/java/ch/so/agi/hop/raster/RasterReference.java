package ch.so.agi.hop.raster;

public record RasterReference(String location, String identity) {
  public RasterReference {
    java.util.Objects.requireNonNull(location);
    if (location.isBlank()) throw new IllegalArgumentException("Raster source is required");
    if (location.startsWith("http://") || location.startsWith("https://")) {
      var uri = java.net.URI.create(location);
      if (uri.getUserInfo() != null)
        throw new IllegalArgumentException("Credentials in raster URLs are unsupported");
    } else {
      if (location.contains("://"))
        throw new IllegalArgumentException("Unsupported raster protocol");
      location = java.nio.file.Path.of(location).toAbsolutePath().normalize().toString();
    }
  }

  public boolean remote() {
    return location.startsWith("http://") || location.startsWith("https://");
  }
}
