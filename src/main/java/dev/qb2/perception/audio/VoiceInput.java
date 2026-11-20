package dev.qb2.perception.audio;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * Zero-dependency Push-to-Talk microphone recorder (16 kHz, 16-bit mono PCM WAV) and async local
 * Whisper transcription client. Runs 100% off the Minecraft render/tick thread.
 */
public final class VoiceInput implements AutoCloseable {
  public static final int SAMPLE_RATE = 16000;
  public static final int MAX_RECORD_BYTES = SAMPLE_RATE * 2 * 15; // 15 seconds max

  public record Result(String text, String error) {}

  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "qb2-voice");
            t.setDaemon(true);
            return t;
          });
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private volatile boolean recording;
  private volatile boolean transcribing;
  private volatile float liveLevel;
  private volatile long recordStartMs;
  private TargetDataLine activeLine;
  private ByteArrayOutputStream pcmBuffer;

  public boolean isRecording() {
    return recording;
  }

  public boolean isTranscribing() {
    return transcribing;
  }

  public float liveLevel() {
    return liveLevel;
  }

  public double recordingSeconds() {
    return recording ? Math.max(0.0, (System.currentTimeMillis() - recordStartMs) / 1000.0) : 0.0;
  }

  public synchronized boolean startRecording() {
    if (recording || transcribing) return false;
    AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
    if (!AudioSystem.isLineSupported(info)) return false;
    try {
      TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
      line.open(fmt, 4096);
      line.start();
      activeLine = line;
      pcmBuffer = new ByteArrayOutputStream(32768);
      recording = true;
      liveLevel = 0f;
      recordStartMs = System.currentTimeMillis();
      executor.submit(() -> captureLoop(line, pcmBuffer));
      return true;
    } catch (Exception e) {
      recording = false;
      activeLine = null;
      return false;
    }
  }

  private void captureLoop(TargetDataLine line, ByteArrayOutputStream out) {
    byte[] chunk = new byte[1024];
    try {
      while (recording && out.size() < MAX_RECORD_BYTES) {
        int n = line.read(chunk, 0, chunk.length);
        if (n > 0) {
          out.write(chunk, 0, n);
          liveLevel = computeRmsLevel(chunk, n);
        }
      }
    } catch (Exception ignored) {
    } finally {
      liveLevel = 0f;
    }
  }

  public synchronized void stopAndTranscribe(
      String endpoint, String language, String bearerToken, Consumer<Result> callback) {
    if (!recording) return;
    recording = false;
    TargetDataLine line = activeLine;
    ByteArrayOutputStream buf = pcmBuffer;
    activeLine = null;
    pcmBuffer = null;
    if (line != null) {
      try {
        line.stop();
        line.close();
      } catch (Exception ignored) {
      }
    }
    byte[] pcm = buf == null ? new byte[0] : buf.toByteArray();
    // Ignore tiny accidental key taps (< 0.25s = 8000 bytes)
    if (pcm.length < 8000) {
      callback.accept(new Result("", ""));
      return;
    }
    byte[] wav = buildWav(pcm);
    transcribing = true;
    executor.submit(
        () -> {
          try {
            String lang =
                language == null || language.isBlank()
                    ? "de"
                    : URLEncoder.encode(language.trim(), StandardCharsets.UTF_8);
            String targetUrl =
                endpoint.contains("?") ? endpoint + "&lang=" + lang : endpoint + "?lang=" + lang;
            dev.qb2.inference.HttpTransport.validateEndpoint(endpoint);
            HttpRequest.Builder req =
                HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "audio/wav")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(wav));
            if (bearerToken != null && !bearerToken.isBlank()) {
              req.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
              callback.accept(new Result("", "Whisper HTTP " + resp.statusCode()));
              return;
            }
            var obj = JsonParser.parseString(resp.body()).getAsJsonObject();
            String text = obj.has("text") ? obj.get("text").getAsString().trim() : "";
            callback.accept(new Result(text, ""));
          } catch (Exception e) {
            callback.accept(new Result("", "Whisper unavailable: " + e.getMessage()));
          } finally {
            transcribing = false;
          }
        });
  }

  public static byte[] buildWav(byte[] pcm16Mono16k) {
    int dataLen = pcm16Mono16k.length;
    ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
    bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    bb.putInt(36 + dataLen);
    bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
    bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    bb.putInt(16); // PCM chunk size
    bb.putShort((short) 1); // PCM format
    bb.putShort((short) 1); // 1 channel (mono)
    bb.putInt(SAMPLE_RATE);
    bb.putInt(SAMPLE_RATE * 2); // byte rate
    bb.putShort((short) 2); // block align
    bb.putShort((short) 16); // bits per sample
    bb.put("data".getBytes(StandardCharsets.US_ASCII));
    bb.putInt(dataLen);
    bb.put(pcm16Mono16k);
    return bb.array();
  }

  static float computeRmsLevel(byte[] pcm, int len) {
    if (len < 2) return 0f;
    double sumSq = 0.0;
    int samples = len / 2;
    for (int i = 0; i + 1 < len; i += 2) {
      short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
      double norm = s / 32768.0;
      sumSq += norm * norm;
    }
    double rms = Math.sqrt(sumSq / Math.max(1, samples));
    return (float) Math.clamp(rms * 5.5, 0.0, 1.0);
  }

  @Override
  public void close() {
    recording = false;
    if (activeLine != null) {
      try {
        activeLine.stop();
        activeLine.close();
      } catch (Exception ignored) {
      }
      activeLine = null;
    }
    executor.shutdownNow();
  }
}
