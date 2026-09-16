package ch.so.agi.hop.raster.geotools;

import it.geosolutions.imageio.core.BasicAuthURI;
import it.geosolutions.imageioimpl.plugins.cog.RangeReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** COG SPI adapter. Never consumes a full-body fallback returned by a server. */
public final class StrictHttpRangeReader implements RangeReader {
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();
  private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
  private final URI uri;
  private int increment;
  private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
  private final Session session;

  public static final class Session {
    byte[] header;
    long length = -1;
    long bytes;
    long requests;
    String validator;

    public Scope activate() {
      return new Scope(this);
    }

    public long bytesRead() {
      return bytes;
    }

    public long requests() {
      return requests;
    }

    public void clear() {
      header = null;
    }
  }

  public static final class Scope implements AutoCloseable {
    private final Session previous;

    private Scope(Session session) {
      previous = CURRENT.get();
      CURRENT.set(session);
    }

    @Override
    public void close() {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  public StrictHttpRangeReader(BasicAuthURI uri, int headerLength) {
    this.uri = uri.getUri();
    this.session = CURRENT.get() == null ? new Session() : CURRENT.get();
    this.increment = headerLength;
  }

  public StrictHttpRangeReader(URI uri, int headerLength) {
    this(new BasicAuthURI(uri), headerLength);
  }

  @Override
  public URL getURL() throws MalformedURLException {
    return uri.toURL();
  }

  @Override
  public void setHeaderLength(int value) {
    increment = value;
  }

  @Override
  public int getHeaderLength() {
    return session.header == null ? increment : session.header.length;
  }

  @Override
  public byte[] readHeader() {
    if (session.header == null) session.header = fetch(0, increment - 1L);
    return session.header;
  }

  @Override
  public byte[] fetchHeader() {
    if (session.header == null) return readHeader();
    if (session.header.length >= 64 * 1024 * 1024)
      throw new IllegalArgumentException("COG metadata exceeds 64 MiB");
    byte[] next = fetch(session.header.length, (long) session.header.length + increment - 1);
    byte[] expanded = Arrays.copyOf(session.header, session.header.length + next.length);
    System.arraycopy(next, 0, expanded, session.header.length, next.length);
    return session.header = expanded;
  }

  @Override
  public Map<Long, byte[]> read(Collection<long[]> ranges) {
    return read(ranges.toArray(long[][]::new));
  }

  @Override
  public Map<Long, byte[]> read(long[]... ranges) {
    Map<Long, byte[]> result = new LinkedHashMap<>();
    for (long[] range : ranges) result.put(range[0], fetch(range[0], range[1]));
    return result;
  }

  private byte[] fetch(long first, long last) {
    if (session.length > 0) last = Math.min(last, session.length - 1);
    if (first < 0 || last < first || last - first >= 64L * 1024 * 1024)
      throw new IllegalArgumentException("Invalid or excessively large COG byte range");
    if (session.header != null && last < session.header.length)
      return Arrays.copyOfRange(session.header, Math.toIntExact(first), Math.toIntExact(last + 1));
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Range", "bytes=" + first + "-" + last)
            .header("Accept-Encoding", "identity");
    if (session.validator != null) builder.header("If-Range", session.validator);
    HttpRequest request = builder.GET().build();
    try {
      HttpResponse<InputStream> response =
          CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        var match = RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
        if (response.statusCode() != 206 || !match.matches())
          throw new IOException(
              "COG server must return HTTP 206 with Content-Range (received "
                  + response.statusCode()
                  + ")");
        long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2));
        long total = Long.parseLong(match.group(3));
        if (start != first
            || end != Math.min(last, total - 1)
            || total <= end
            || (session.length > 0 && total != session.length))
          throw new IOException("Unexpected or changing COG Content-Range");
        session.length = total;
        String validator =
            response
                .headers()
                .firstValue("ETag")
                .filter(v -> !v.startsWith("W/"))
                .orElseGet(() -> response.headers().firstValue("Last-Modified").orElse(null));
        if (session.validator != null && !session.validator.equals(validator))
          throw new IOException("COG changed while reading");
        session.validator = validator;
        int count = Math.toIntExact(end - start + 1);
        byte[] bytes = body.readNBytes(count);
        if (bytes.length != count || body.read() != -1)
          throw new IOException("Incorrect COG range body length");
        session.bytes += bytes.length;
        session.requests++;
        return bytes;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new IOException("COG request interrupted", e));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
