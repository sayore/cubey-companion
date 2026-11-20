package dev.qb2.inference;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

public final class HttpTransport implements AutoCloseable {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public static URI validateEndpoint(String value) {
    URI uri = URI.create(value);
    String host = uri.getHost();
    boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    if (host == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme())))
      throw new IllegalArgumentException("Use HTTPS or loopback HTTP without credentials/query");
    return uri;
  }

  public String post(String url, String key, boolean google, String body, int timeout)
      throws IOException, InterruptedException {
    URI uri = validateEndpoint(url);
    for (int attempt = 0; attempt < 3; attempt++) {
      var builder =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(timeout))
              .header("Content-Type", "application/json");
      if (!key.isBlank())
        builder.header(google ? "x-goog-api-key" : "Authorization", google ? key : "Bearer " + key);
      var future =
          client.sendAsync(
              builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
              info -> new LimitedBody());
      HttpResponse<byte[]> response;
      try {
        response = future.get(timeout, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        future.cancel(true);
        throw e;
      } catch (TimeoutException e) {
        future.cancel(true);
        if (attempt == 2) throw new IOException("API timeout");
        Thread.sleep(500L << attempt);
        continue;
      } catch (ExecutionException e) {
        future.cancel(true);
        if (e.getCause() instanceof IOException && attempt < 2) {
          Thread.sleep(500L << attempt);
          continue;
        }
        throw new IOException("API transport failed");
      }
      int status = response.statusCode();
      if (status >= 200 && status < 300)
        return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
      if (attempt < 2 && (status == 429 || status >= 500)) {
        Thread.sleep(500L << attempt);
        continue;
      }
      throw new IOException("API HTTP " + status);
    }
    throw new IOException("API unavailable");
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int size;

    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      delegate.onSubscribe(s);
    }

    public void onNext(List<ByteBuffer> chunks) {
      for (var chunk : chunks) size += chunk.remaining();
      if (size > 262144) {
        subscription.cancel();
        delegate.onError(new IOException("Response too large"));
      } else delegate.onNext(chunks);
    }

    public void onError(Throwable error) {
      delegate.onError(error);
    }

    public void onComplete() {
      delegate.onComplete();
    }
  }

  public void close() {
    client.shutdownNow();
  }
}
