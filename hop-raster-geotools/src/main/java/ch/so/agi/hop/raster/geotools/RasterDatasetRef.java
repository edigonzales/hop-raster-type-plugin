package ch.so.agi.hop.raster.geotools;

import java.net.URI;
import java.nio.file.Path;

public record RasterDatasetRef(String location) {
  public RasterDatasetRef {
    if (location == null || location.isBlank())
      throw new IllegalArgumentException("Raster source is required");
    location = location.trim();
    if (location.contains("://")
        && !(location.startsWith("http://") || location.startsWith("https://")))
      throw new IllegalArgumentException(
          "Only local paths and public HTTP/HTTPS URLs are supported");
    if (location.startsWith("http://") || location.startsWith("https://")) {
      URI uri = URI.create(location);
      if (uri.getUserInfo() != null)
        throw new IllegalArgumentException("Authenticated URLs are not supported");
    } else location = Path.of(location).toAbsolutePath().normalize().toString();
  }

  public boolean remote() {
    return location.startsWith("http://") || location.startsWith("https://");
  }
}
