package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.model.Codec;
import com.genymobile.scrcpy.model.CodecOption;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.CodecUtils;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SurfaceEncoder implements AsyncProcessor {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    // Keep the values in descending order
    private static final int[] MAX_SIZE_FALLBACK = {2560, 1920, 1600, 1280, 1024, 800};
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    // === ABR: short-window adaptive bitrate reduction ===
    // When the encoder is overloaded (output frame rate drops well below
    // the target for a short window, e.g. during boot animations or scene
    // switches), reduce the bitrate step by step so the stream stays
    // smooth (blurrier but not stuttering). Resolution is never changed.
    private static final long ABR_WINDOW_NS = 50_000_000L; // 50ms detection window: boot animations and
                                                       // scene-switch spikes are short; at 120fps a 200ms
                                                       // window expects ~24 frames and catches short spikes
                                                       // without being diluted by a longer average.
    private static final int ABR_MIN_FRAMES_IN_WINDOW = 5; // 200ms window: fewer than 5 frames means the
                                                      // stream is below ~25fps, treat as static screen
                                                      // (loading screens / low activity) and ignore.
    private static final float ABR_RATIO_THRESHOLD = 0.80f; // healthy if actual >= 80% of expected
    private static final long ABR_MIN_DOWN_INTERVAL_NS = 200_000_000L; // 200ms debounce between downgrades (fast, less backlog)
    private static final long ABR_UP_DELAY_NS = 150_000_000L; // stable for 150ms before trying to raise (fast recovery)
    private static final long ABR_MIN_UP_INTERVAL_NS = 150_000_000L; // 150ms debounce between raises (fast recovery)
    // Ceiling release: after this long without overload while capped, the
    // recovery ceiling is slowly relaxed (x1.1 per window) up to the initial rate.
    private static final long ABR_CEILING_RELEASE_DELAY_NS = 15_000_000_000L; // 15s (faster ceiling probe)
    private static final int ABR_MIN_BITRATE = 1_000_000; // floor (1M, aggressive experiment)
    private static final long ABR_DELAY_TRIGGER_MS = 30; // window avg delay delta (ms) above baseline -> overloaded (more sensitive)
    // Instant single-frame trigger: a single frame whose calibrated
    // delayDelta exceeds this threshold degrades immediately (no window
    // wait), catching startup bursts within ~10ms. Debounce is 80ms so
    // the 3-step chain (60->18->5.4->1.62->1M) completes in ~240ms.
    private static final long ABR_INSTANT_TRIGGER_MS = 30;
    private static final long ABR_INSTANT_INTERVAL_NS = 15_000_000L; // 15ms debounce between instant triggers (3-step chain 60->18->5.4->1.62->1M in ~60ms)
    // Kept at 50ms: a 200ms window averages over fewer frames, so the
    // mean is more volatile and the same 50ms threshold is already more
    // sensitive than with a 500ms window (a short spike contributes a
    // larger share of the average). Lowering it further (e.g. 40ms)
    // would risk false downgrades on ordinary scrolling jitter.
    private static final long ABR_DELAY_RECOVER_MS = 20; // window avg delay delta below this -> healthy

    private int currentBitRate;
    // Recovery ceiling (TCP-like congestion control): the restore phase must
    // not exceed this rate. Shrunk to 80% of the pre-drop rate on every
    // overload so the restore converges to a stable bitrate instead of
    // oscillating drop -> restore -> drop (e.g. 60M overloaded forever).
    private int abrCeiling;
    private long abrCeilingStableSinceNs;
    private long abrWindowStartNs;
    private int abrWindowFrames;
    private long abrLastDownNs;
    private long abrStableSinceNs;
    private long abrLastUpNs;
    private int abrLastWindowActual;
    // Output delay tracking: ptsUs comes from a monotonic clock whose base
    // differs from elapsedRealtimeNanos (observed ~300s offset on K80), so
    // only the delta from the calibrated baseline is meaningful.
    private static final int ABR_CALIB_FRAMES = 10;
    private long abrCalibSum;
    private int abrCalibCount;
    private long abrBaselineDelayMs;
    private long abrWindowDelaySum;
    private int abrWindowDelayCount;
    private boolean abrRestoring; // bitrate was raised; next overload must degrade immediately
    private boolean abrStateInitialized; // ABR state kept across encoder rebuilds
    // Pulse detection for absolute-latency measurement: frames whose PTS
    // gap from the previous frame exceeds 500ms are pulse frames (the test
    // video flashes a red frame every second). Log the PTS so the PC-side
    // script can timestamp the pulse via logcat arrival time.
    private long pulseLastPtsUs = -1;
    private long pulseLastLogNs;
    private long frameLogLastNs; // rate-limit the FRAME diagnosis log
    // Frame-complexity lookahead: the encoded frame size reflects content
    // complexity (a complex frame costs more to encode, so the current
    // frame is already slow). A frame larger than the per-frame bitrate
    // budget x1.5 degrades immediately (one-frame response ~8ms, before
    // the backlog-based detection fires ~75ms later), giving preventive
    // low bitrate during animations. Static frames are small and never
    // trigger, keeping high bitrate on still scenes.
    private static final int ABR_COMPLEXITY_FACTOR = 3; // x1.5 as budget*3/2
    private long abrLastComplexLogNs; // rate-limit the complex-frame log (1/s)

    private final SurfaceCapture capture;
    private final Streamer streamer;
    private final String encoderName;
    private final List<CodecOption> codecOptions;
    private final int videoBitRate;
    private final int maxSize;
    private final float maxFps;
    private final boolean downsizeOnError;
    private final int minSizeAlignment;
    private final boolean ignoreVideoEncoderConstraints;

    private boolean firstFrameSent;
    private int consecutiveErrors;

    private Thread thread;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private final CaptureControl captureControl = new CaptureControl();

    private VideoConstraints videoConstraints;

    public SurfaceEncoder(SurfaceCapture capture, Streamer streamer, Options options) {
        this.capture = capture;
        this.streamer = streamer;
        this.videoBitRate = options.getVideoBitRate();
        this.maxSize = options.getMaxSize();
        this.maxFps = options.getMaxFps();
        this.codecOptions = options.getVideoCodecOptions();
        this.encoderName = options.getVideoEncoder();
        this.downsizeOnError = options.getDownsizeOnError();
        this.minSizeAlignment = options.getMinSizeAlignment();
        this.ignoreVideoEncoderConstraints = options.getIgnoreVideoEncoderConstraints();
    }

    private void streamCapture() throws IOException, ConfigurationException {
        Codec codec = streamer.getCodec();
        MediaCodec mediaCodec = createMediaCodec(codec, encoderName);
        MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, maxFps, codecOptions);

        MediaCodecInfo.VideoCapabilities caps;
        int alignment;
        if (ignoreVideoEncoderConstraints) {
            caps = null;
            alignment = 1;
        } else {
            caps = mediaCodec.getCodecInfo().getCapabilitiesForType(codec.getMimeType()).getVideoCapabilities();
            assert caps != null; // caps cannot be null for a video codec
            alignment = Math.max(caps.getWidthAlignment(), caps.getHeightAlignment());
            Ln.d("Video codec size alignment requirement: " + alignment + "px");
        }
        if (alignment < minSizeAlignment) {
            alignment = minSizeAlignment;
            Ln.d("Actual video size alignment: " + alignment + "px");
        }

        // Do not constrain by the declared video encoder capabilities before encoding actually fails
        videoConstraints = new VideoConstraints(maxSize, alignment, null);

        capture.init(captureControl, videoConstraints);

        try {
            boolean alive;

            streamer.writeVideoHeader();

            int retainedResetReasons = 0;

            do {
                int resetReasons = captureControl.consumeReset();
                if ((resetReasons & CaptureControl.RESET_REASON_TERMINATED) != 0) {
                    break;
                }
                if (retainedResetReasons != 0) {
                    // The reasons for the previous failed encoding must be preserved when retrying
                    resetReasons |= retainedResetReasons;
                    retainedResetReasons = 0;
                }

                capture.prepare();
                Size size = capture.getSize();

                format.setInteger(MediaFormat.KEY_WIDTH, size.getWidth());
                format.setInteger(MediaFormat.KEY_HEIGHT, size.getHeight());

                Surface surface = null;
                boolean mediaCodecStarted = false;
                boolean captureStarted = false;
                try {
                    mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = mediaCodec.createInputSurface();

                    capture.start(surface);
                    captureStarted = true;

                    mediaCodec.start();
                    mediaCodecStarted = true;

                    // Set the MediaCodec instance to "interrupt" (by signaling an EOS) on reset
                    captureControl.setRunningMediaCodec(mediaCodec);

                    if (stopped.get()) {
                        alive = false;
                    } else {
                        if (!captureControl.isResetRequested()) {
                            // The reset is due to a resize initiated by the client
                            boolean isClientResize = (resetReasons & CaptureControl.RESET_REASON_CLIENT_RESIZED) != 0
                                    && (resetReasons & CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED) == 0;
                            streamer.writeSessionMeta(size.getWidth(), size.getHeight(), isClientResize);

                            // If a reset is requested during encode(), it will interrupt the encoding by an EOS
                            encode(mediaCodec, streamer);
                        }

                        // The capture might have been closed internally (for example if the camera is disconnected)
                        alive = !stopped.get() && !capture.isClosed();
                    }
                } catch (IllegalStateException | IllegalArgumentException | IOException e) {
                    if (IO.isBrokenPipe(e)) {
                        // Do not retry on broken pipe, which is expected on close because the socket is closed by the client
                        throw e;
                    }
                    Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                    if (!prepareRetry(caps, size)) {
                        throw e;
                    }
                    // Keep the current resetReasons flags for the retry
                    retainedResetReasons = resetReasons;
                    alive = true;
                } finally {
                    captureControl.setRunningMediaCodec(null);
                    if (captureStarted) {
                        capture.stop();
                    }
                    if (mediaCodecStarted) {
                        try {
                            mediaCodec.stop();
                        } catch (IllegalStateException e) {
                            // ignore (just in case)
                        }
                    }
                    mediaCodec.reset();
                    if (surface != null) {
                        surface.release();
                    }
                }
            } while (alive);
        } finally {
            mediaCodec.release();
            capture.release();
        }
    }

    private boolean prepareRetry(MediaCodecInfo.VideoCapabilities caps, Size currentSize) {
        if (firstFrameSent) {
            ++consecutiveErrors;
            if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                // Wait a bit to increase the probability that retrying will fix the problem
                SystemClock.sleep(50);
                return true;
            }
        }

        if (!downsizeOnError) {
            // Must fail immediately
            return false;
        }

        if (caps != null && videoConstraints.getEncoderCapabilities() == null) {
            assert !ignoreVideoEncoderConstraints : "caps != null implies !ignoreVideoEncoderConstraints";
            Ln.i("Applying video encoder constraints");
            videoConstraints = videoConstraints.withCapabilities(caps);
            boolean accepted = capture.applyNewVideoConstraints(videoConstraints);
            if (accepted) {
                return true;
            }
        }

        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            // Definitively fail
            return false;
        }

        // Downsizing on error is only enabled if an encoding failure occurs before the first frame, or if the video constraints were not applied
        // (downsizing later could be surprising)

        int newMaxSize = chooseMaxSizeFallback(currentSize);
        if (newMaxSize == 0) {
            // Must definitively fail
            return false;
        }

        boolean accepted = capture.applyNewVideoConstraints(videoConstraints.withMaxSize(newMaxSize));
        if (!accepted) {
            return false;
        }

        // Retry with a smaller size
        Ln.i("Retrying with -m" + newMaxSize + "...");
        return true;
    }

    private static int chooseMaxSizeFallback(Size failedSize) {
        int currentMaxSize = Math.max(failedSize.getWidth(), failedSize.getHeight());
        for (int value : MAX_SIZE_FALLBACK) {
            if (value < currentMaxSize) {
                // We found a smaller value to reduce the video size
                return value;
            }
        }
        // No fallback, fail definitively
        return 0;
    }

    private void encode(MediaCodec codec, Streamer streamer) throws IOException {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        if (!abrStateInitialized) {
            // First encoder session: start from the configured bitrate.
            currentBitRate = videoBitRate;
            abrCeiling = videoBitRate;
            abrStateInitialized = true;
        } else {
            // Encoder rebuilt (rotation/size change -> MediaCodec reset):
            // KEEP the ABR state instead of resetting to the initial bitrate
            // (which would re-accumulate backlog and oscillate), and re-apply
            // the kept bitrate to the new encoder instance.
            Bundle p = new Bundle();
            p.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, currentBitRate);
            codec.setParameters(p);
            Ln.i("ABR: encoder rebuilt, keeping bitrate=" + currentBitRate
                    + " ceiling=" + abrCeiling);
        }
        // Window/calibration state always resets for the new session.
        abrCeilingStableSinceNs = 0;
        abrWindowStartNs = 0;
        abrWindowFrames = 0;
        abrLastDownNs = 0;
        abrLastUpNs = 0;
        abrStableSinceNs = 0;
        abrCalibSum = 0;
        abrCalibCount = 0;
        abrBaselineDelayMs = 0;
        abrWindowDelaySum = 0;
        abrWindowDelayCount = 0;

        boolean eos;
        do {
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1);
            try {
                eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                // On EOS, there might be data or not, depending on bufferInfo.size
                if (outputBufferId >= 0 && bufferInfo.size > 0) {
                    boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!isConfig) {
                        // If this is not a config packet, then it contains a frame
                        firstFrameSent = true;
                        consecutiveErrors = 0;
                        long ptsUs = bufferInfo.presentationTimeUs;
                        long nowNs = SystemClock.elapsedRealtimeNanos();
                        maybeAdaptBitrate(codec, ptsUs, nowNs);
                        // Frame-complexity lookahead: complex frame (bigger
                        // than the per-frame bitrate budget x1.5) -> degrade
                        // immediately, before the backlog-based detection ever
                        // fires. Budget uses the current frame rate estimated
                        // from the frame gap (normal 8-17ms; a gap >500ms is a
                        // dropped frame, not a normal frame interval).
                        long gapUs = ptsUs - pulseLastPtsUs;
                        if (gapUs > 0 && gapUs < 500_000) {
                            long fps = 1_000_000 / gapUs;
                            if (fps > 0) {
                                long budgetBytes = currentBitRate / fps / 8;
                                if (budgetBytes > 0
                                        && bufferInfo.size > budgetBytes * ABR_COMPLEXITY_FACTOR / 2
                                        && currentBitRate > ABR_MIN_BITRATE
                                        && nowNs - abrLastDownNs >= ABR_INSTANT_INTERVAL_NS) {
                                    abrStableSinceNs = 0;
                                    abrRestoring = false;
                                    if (nowNs - abrLastComplexLogNs >= 1_000_000_000L) {
                                        Ln.i("ABR: complex frame (bytes="
                                                + (bufferInfo.size / 1024) + "KB > budget x1.5), degrading");
                                        abrLastComplexLogNs = nowNs;
                                    }
                                    lowerBitrate(codec, nowNs);
                                }
                            }
                        }
                        // Absolute-latency pulse detection: PTS gap > 500ms
                        // marks a pulse frame (test video: 1 red frame per
                        // second). Rate-limited to 200ms so one pulse logs
                        // once (the 83ms pulse spans ~5 frames, only the
                        // first has a gap > 500ms anyway).
                        if (pulseLastPtsUs >= 0
                                && ptsUs - pulseLastPtsUs > 500_000
                                && nowNs - pulseLastLogNs >= 200_000_000L) {
                            Ln.i("PULSE: pts=" + ptsUs);
                            pulseLastLogNs = nowNs;
                        }
                        pulseLastPtsUs = ptsUs;
                        // Frame diagnosis: type (I/P) + calibrated delay
                        // delta + size. I frames always logged; P frames when
                        // delayed >100ms or sampled every 500ms (no spam).
                        // delayDelta = encode duration of this frame (the
                        // suspect for the splash first-frame latency: a large
                        // 2K I frame may take 200-500ms to encode).
                        boolean isKey = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        long fdelay = abrBaselineDelayMs > 0
                                ? (nowNs - ptsUs * 1000) / 1_000_000 - abrBaselineDelayMs : -1;
                        if (isKey || fdelay > 100 || nowNs - frameLogLastNs >= 500_000_000L) {
                            Ln.i("FRAME: type=" + (isKey ? "I" : "P") + " pts=" + ptsUs
                                    + " delayDelta=" + fdelay + "ms size="
                                    + (bufferInfo.size / 1024) + "KB");
                            frameLogLastNs = nowNs;
                        }
                    }

                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);
                    streamer.writePacket(codecBuffer, bufferInfo);
                }
            } finally {
                if (outputBufferId >= 0) {
                    codec.releaseOutputBuffer(outputBufferId, false);
                }
            }
        } while (!eos);
    }

    /**
     * Short-window encoder overload detection and adaptive bitrate reduction.
     * Called once per encoded (output) frame. Every ABR_WINDOW_NS, compare the
     * actual number of encoded frames with the expected number (maxFps x
     * window). If the screen is not static and the ratio is too low, the
     * encoder is overloaded: step the bitrate down (seamless via
     * MediaCodec.setParameters, no resolution change). After a stable period
     * the bitrate is raised back step by step.
     */
    private void maybeAdaptBitrate(MediaCodec codec, long ptsUs, long nowNs) {
        // Delay calibration: the first frames measure the baseline encoder
        // delay (ptsUs and elapsedRealtime use different monotonic bases).
        long delayMs = (nowNs - ptsUs * 1000) / 1_000_000;
        if (abrCalibCount < ABR_CALIB_FRAMES) {
            abrCalibSum += delayMs;
            abrCalibCount++;
            if (abrCalibCount == ABR_CALIB_FRAMES) {
                abrBaselineDelayMs = abrCalibSum / ABR_CALIB_FRAMES;
                Ln.i("ABR: delay baseline=" + abrBaselineDelayMs + "ms");
                // Clock anchor for absolute-latency measurement: the device
                // epoch at this SystemClock instant. PTS is device
                // SystemClock (uptime-based) microseconds, NOT epoch; device
                // encoding time (epoch) of any frame is boot_epoch + pts/1000
                // where boot_epoch = epoch - pts_us/1000 captured here. The
                // pc-side script parses this line to convert frame pts to
                // device epoch, then applies its own pc<->device offset.
                Ln.i("ABR: clock anchor pts_us=" + ptsUs
                        + " epoch=" + System.currentTimeMillis());
            }
            return;
        }
        long delayDelta = delayMs - abrBaselineDelayMs;
        // Instant single-frame trigger: one frame over the threshold degrades
        // immediately (startup bursts are caught within ~10ms instead of
        // waiting a full window). Unified 80ms debounce for the down chain;
        // the window detection below remains as a backstop. Restore/ceiling
        // mechanism is untouched.
        if (delayDelta > ABR_INSTANT_TRIGGER_MS
                && currentBitRate > ABR_MIN_BITRATE
                && nowNs - abrLastDownNs >= ABR_INSTANT_INTERVAL_NS) {
            abrStableSinceNs = 0;
            abrRestoring = false;
            Ln.i("ABR: instant overload (delayDelta=" + delayDelta + "ms), degrading");
            lowerBitrate(codec, nowNs);
        }
        abrWindowDelaySum += delayDelta;
        abrWindowDelayCount++;

        if (abrWindowStartNs == 0) {
            abrWindowStartNs = nowNs;
            abrWindowFrames = 1;
            return;
        }
        abrWindowFrames++;
        long elapsed = nowNs - abrWindowStartNs;
        if (elapsed < ABR_WINDOW_NS) {
            return;
        }

        // Window complete: evaluate both overload signals.
        long expected = (long) (maxFps * (elapsed / 1_000_000_000.0));
        int actual = abrWindowFrames;
        abrLastWindowActual = actual;
        long delayAvg = abrWindowDelayCount > 0
                ? abrWindowDelaySum / abrWindowDelayCount : 0;
        abrWindowStartNs = nowNs;
        abrWindowFrames = 0;
        abrWindowDelaySum = 0;
        abrWindowDelayCount = 0;

        // Static screens produce almost no frames: never treat them as overload.
        boolean staticScreen = actual < ABR_MIN_FRAMES_IN_WINDOW;
        boolean frameRateOk = expected < 5 || actual >= expected * ABR_RATIO_THRESHOLD;
        boolean delayOk = delayAvg < ABR_DELAY_TRIGGER_MS;
        // Only the encoder output delay (delta vs calibrated baseline) is the
        // overload signal: a low frame count alone is NOT overload (the source
        // may render few frames, e.g. animations or idle scenes).
        boolean healthy = staticScreen || delayOk;

        if (!healthy) {
            abrStableSinceNs = 0;
            // Overload during a restore phase must interrupt the restore
            // immediately: skip the down-debounce so the bitrate drops right
            // away instead of staying high while the page keeps stuttering.
            boolean restoring = abrRestoring;
            abrRestoring = false;
            if (currentBitRate > ABR_MIN_BITRATE
                    && (restoring || nowNs - abrLastDownNs >= ABR_MIN_DOWN_INTERVAL_NS)) {
                Ln.i("ABR: overloaded"
                        + (restoring ? " during restore, degrading" : "")
                        + " (delayDelta=" + delayAvg
                        + "ms, frames=" + actual + "/" + expected + ")");
                lowerBitrate(codec, nowNs);
            }
            return;
        }

        // Healthy: recover step by step after a stable period.
        if (abrStableSinceNs == 0) {
            abrStableSinceNs = nowNs;
        } else if (nowNs - abrStableSinceNs >= ABR_UP_DELAY_NS
                && currentBitRate < videoBitRate
                && nowNs - abrLastUpNs >= ABR_MIN_UP_INTERVAL_NS
                && delayAvg < ABR_DELAY_RECOVER_MS) {
            // reached the target again: restore phase finished
            abrRestoring = false;
            raiseBitrate(codec, nowNs);
        }
    }

    private void lowerBitrate(MediaCodec codec, long nowNs) {
        // Aggressive down-shift: x0.3 (60M -> 18M -> 5.4M -> 1.62M -> 1M)
        // so mid/high bitrates drop instantly on stutter.
        int newRate = Math.max(ABR_MIN_BITRATE, currentBitRate * 3 / 10);
        if (newRate == currentBitRate) {
            return;
        }
        // Recovery ceiling = the downgraded rate itself: the restore must
        // not climb back above the rate that just overloaded the encoder
        // (oscillation guard). The ceiling is slowly released (x1.1) after
        // ABR_CEILING_RELEASE_DELAY_NS without overload.
        abrCeiling = newRate;
        abrCeilingStableSinceNs = 0;
        Ln.i("ABR: ceiling capped at " + abrCeiling + " (oscillation guard)");
        Ln.i("ABR: bitrate " + currentBitRate + " -> " + newRate);
        applyBitrate(codec, newRate);
        currentBitRate = newRate;
        abrLastDownNs = nowNs;
    }

    private void raiseBitrate(MediaCodec codec, long nowNs) {
        // Segmented recovery:
        //   < 10M:   x1.8  fast climb out of the valley (1M -> 1.8M -> 3.24M)
        //   10-30M:  x1.5  medium steps (16.2M -> 24.3M -> 36.4M)
        //   >= 30M:  x1.2  fine steps, avoid jumping back so hard that
        //                 the encoder overloads again (36.4M -> 43.7M -> ...)
        int factorNum = 6;
        int factorDen = 5;
        if (currentBitRate < 10_000_000) {
            factorNum = 9;
            factorDen = 5;
        } else if (currentBitRate < 30_000_000) {
            factorNum = 3;
            factorDen = 2;
        }
        int target = currentBitRate * factorNum / factorDen;
        boolean capped = target > abrCeiling;
        if (capped) {
            target = abrCeiling;
        }
        int newRate = Math.min(videoBitRate, target);
        if (newRate == currentBitRate) {
            // Already at the ceiling: start/keep the stable timer, and
            // slowly release the ceiling after a long healthy period.
            if (abrCeilingStableSinceNs == 0) {
                abrCeilingStableSinceNs = nowNs;
            } else if (abrCeiling < videoBitRate
                    && nowNs - abrCeilingStableSinceNs >= ABR_CEILING_RELEASE_DELAY_NS) {
                int oldCeiling = abrCeiling;
                abrCeiling = Math.min(videoBitRate, abrCeiling * 11 / 10);
                abrCeilingStableSinceNs = nowNs;
                Ln.i("ABR: ceiling released " + oldCeiling + " -> " + abrCeiling);
            }
            return;
        }
        Ln.i("ABR: stable, restoring bitrate " + currentBitRate + " -> " + newRate
                + (capped ? " (capped at ceiling " + abrCeiling + ")" : ""));
        applyBitrate(codec, newRate);
        currentBitRate = newRate;
        abrLastUpNs = nowNs;
        if (currentBitRate >= abrCeiling) {
            if (abrCeilingStableSinceNs == 0) {
                abrCeilingStableSinceNs = nowNs;
            }
        } else {
            abrCeilingStableSinceNs = 0;
        }
        abrRestoring = true; // an overload in the next window must degrade immediately
    }

    private void applyBitrate(MediaCodec codec, int bitrate) {
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
            codec.setParameters(params);
        } catch (Exception e) {
            Ln.w("ABR: setParameters failed: " + e.getMessage());
        }
    }

    private static MediaCodec createMediaCodec(Codec codec, String encoderName) throws IOException, ConfigurationException {
        if (encoderName != null) {
            Ln.d("Creating encoder by name: '" + encoderName + "'");
            try {
                MediaCodec mediaCodec = MediaCodec.createByCodecName(encoderName);
                String mimeType = Codec.getMimeType(mediaCodec);
                if (!codec.getMimeType().equals(mimeType)) {
                    Ln.e("Video encoder type for \"" + encoderName + "\" (" + mimeType + ") does not match codec type (" + codec.getMimeType() + ")");
                    throw new ConfigurationException("Incorrect encoder type: " + encoderName);
                }
                return mediaCodec;
            } catch (IllegalArgumentException e) {
                Ln.e("Video encoder '" + encoderName + "' for " + codec.getName() + " not found\n" + LogUtils.buildVideoEncoderListMessage());
                throw new ConfigurationException("Unknown encoder: " + encoderName);
            } catch (IOException e) {
                Ln.e("Could not create video encoder '" + encoderName + "' for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
                throw e;
            }
        }

        try {
            MediaCodec mediaCodec = MediaCodec.createEncoderByType(codec.getMimeType());
            Ln.d("Using video encoder: '" + mediaCodec.getName() + "'");
            return mediaCodec;
        } catch (IOException | IllegalArgumentException e) {
            Ln.e("Could not create default video encoder for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
            throw e;
        }
    }

    private static MediaFormat createFormat(String videoMimeType, int bitRate, float maxFps, List<CodecOption> codecOptions) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, videoMimeType);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_24_ANDROID_7_0) {
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
        // display the very first frame, and recover from bad quality when no new frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US); // µs
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0) {
            // real-time priority
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
            // output 1 frame as soon as 1 frame is queued
            format.setInteger(MediaFormat.KEY_LATENCY, 1);
            // Splash "traffic jam" mitigation (solutions 1+2): the 374KB
            // I frame of the splash animation stalls the encoder (input
            // Surface queue backlog, delayDelta up to ~317ms). Low-latency
            // mode speeds up single-frame encoding; a coarser I-frame QP
            // makes the I frame smaller (374 -> ~150-200KB) so it encodes
            // faster. Both are ignored silently by encoders that do not
            // support them.
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            format.setInteger("i-frame-qp", 32);

        }
        if (maxFps > 0) {
            // The key existed privately before Android 10:
            // <https://android.googlesource.com/platform/frameworks/base/+/625f0aad9f7a259b6881006ad8710adce57d1384%5E%21/>
            // <https://github.com/Genymobile/scrcpy/issues/488#issuecomment-567321437>
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                String key = option.getKey();
                Object value = option.getValue();
                CodecUtils.setCodecOption(format, key, value);
                Ln.d("Video codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
            }
        }

        return format;
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            // Some devices (Meizu) deadlock if the video encoding thread has no Looper
            // <https://github.com/Genymobile/scrcpy/issues/4143>
            Looper.prepare();

            try {
                streamCapture();
            } catch (ConfigurationException e) {
                // Do not print stack trace, a user-friendly error-message has already been logged
            } catch (IOException e) {
                // Broken pipe is expected on close, because the socket is closed by the client
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Video encoding error", e);
                }
            } finally {
                Ln.d("Screen streaming stopped");
                listener.onTerminated(true);
            }
        }, "video");
        thread.start();
    }

    @Override
    public void stop() {
        if (thread != null) {
            stopped.set(true);
            captureControl.reset(CaptureControl.RESET_REASON_TERMINATED);
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
}
