# High-resolution playback

Visible patch: **High-resolution playback**, selected by default. Runtime preference:
`ModSettings.MEDIA_TRY_EXCEED_CAPABILITIES` (`media.tryExceedCapabilities`), default `true`.
No additional Media3 library, native decoder, or transcoder is included.

## Findings in versionCode 2081362

The bundled `MediaCodecInfo.isFormatSupported` checks the codec profile/level and then
the advertised video size/rate. The size/rate method contains `sizeAndRate.caps`,
`sizeAndRate.vCaps`, `sizeAndRate.cover`, and `sizeAndRate.support` diagnostics. It uses
Android `VideoCapabilities`, including performance points and aligned size/rate checks.
A 1440p/2160p stream can therefore fail the capability check on a decoder reporting 1080p.
`MediaCodecVideoRenderer.supportsFormat` reports support **3** (`FORMAT_EXCEEDS_CAPABILITIES`)
when none of the available decoders reports full support, rather than rejecting it as an
unsupported MIME type or DRM scheme.

The selector's `exceedRendererCapabilitiesIfNecessary` is already **true** by default.
Its support predicate accepts support **4**, or support **3** when that flag is true.
Files' error wrapper subsequently sets the flag to **false**, logs
`Trying to disable exceed renderer capabilities.`, and prepares again after a renderer
error with support 3. That change makes the selector reject the oversized track on retry.

There is a second, independent restriction: R8 removed the constant-false
`enableDecoderFallback` option. The renderer creates an empty `ArrayDeque` and queues
only `decoderInfos.get(0)`. Its existing failure handler removes that decoder and retries
the next queue entry, but normally the queue is already empty. Thus an initialization
failure of the preferred hardware decoder never reaches another platform codec.

With the preference enabled, the patch populates this same queue with the entire original
selector result, preserving preference order. This includes `c2.android.*` software codecs
when Android enumerates them. The existing Media3 retry loop releases a failed codec and
tries the next one. Individual codec failures are logged even if a later decoder succeeds.
After exhaustion, Files' wrapper forwards the error to its original delegate immediately,
preserving the default capability allowance and avoiding repeated prepare/skip recovery.

The patch deliberately retains actual support verdicts, profile/MIME/DRM checks, and buffer
sizes. In this build, codec max input size is calculated from the actual format dimensions
and initialization data; it does not contain a fixed 1080p ceiling. The renderer also logs
`Format exceeds selected codec's capabilities` after successful initialization, so that
particular check is informational, not a decoder-init veto. There is no reason to lower
the format dimensions, fabricate a supported verdict, or arbitrarily inflate buffers.

With the preference disabled, the queue contains only the original preferred decoder and
Files' original error handler runs. Open a new preview after changing the preference.
Diagnostics remain enabled in either mode. The integration/settings worker owns the
settings screen and Application initialization; this patch depends on its
`modKitIntegrationPatch` entry point.

## Device results (OPPO OPG02 = A54 5G, Android 12, v1.0.0-dev.2, 2026-11-11)

Decoder limits (from the in-app diagnostics):
- All hardware decoders (`c2.qti.*` / `OMX.qcom.*`, AVC/HEVC/VP9): max **1920 x 1920** per side, 1080p60 only. No 1440p or 2160p.
- Software decoders: `c2.android.avc.decoder` max 2048 x 2048 (1080p60 OK). `c2.android.hevc.decoder` max 2048 x 2048 but not even 1080p60. VP9 software max 1280. AV1 software only (max 1280 / 1920x1072).
- So any video with a side > 1920 cannot use the hardware decoder, and anything > 2048 has no platform decoder at all.

Observed with a 1596 x 1996 AVC video (24 fps, 9.9 Mbps):
- Media3 reported `support=3` (exceeds capabilities) and, thanks to the patch, still tried to play.
- `c2.qti.avc.decoder` and `.low_latency` failed in `MediaCodec.configure` (IllegalArgumentException), because the height 1996 > 1920.
- Decoder fallback (this patch) then initialized `c2.android.avc.decoder` (software) and no player error followed.
- Fallback also applies to audio decoders (harmless; log line "candidates=[c2.android.aac.decoder]").
- Log wording to fix: audio formats are logged as "Video input".

Result: the 1596 x 1996 video **played fine** (user confirmed).

Observed with a 3840 x 2160 AVC video (High@5.1 `avc1.640033`, 30 fps, 28.6 Mbps):
- Same path: both `c2.qti.avc.*` fail in `configure`, then `c2.android.avc.decoder` initializes even though it advertises max 2048 x 2048 (the advertised limit is not enforced).
- It plays, but **stutters** (software decode too slow for 4K30 on Snapdragon 480).
- VLC for Android plays the same file **without stutter** -> a solution exists. Most likely VLC decodes with its own multi-threaded FFmpeg (libavcodec, possibly skipping the H.264 loop filter). To be confirmed by the user with VLC's hardware acceleration set to "Disabled".

Consequences for the plan (DESIGN §5.5):
- 2K AVC whose sides are <= 2048 (e.g. 2048x1080, portrait ~1600x2000): L1 + L2 (software AVC) is the path. Smoothness still to be confirmed by the user.
- 2560x1440 and 4K: no platform decoder can open them. Needs L4 (keyframe-only via a bundled decoder) or L3 (transcode), i.e. a bundled decoder (FFmpeg / dav1d) -> Phase 5-6.
- HEVC above 1080p: software HEVC is too slow even for 1080p60; expect L3/L4 only.

## Fingerprints and hooks

* Preview initialization: `bufferForPlaybackMs` / `maxBufferMs`, no parameters, void return,
  and a field of the kept `VideoPlayerView` type. Attach immediately after the builder's
  return of the kept `androidx.media3.exoplayer.ExoPlayer` interface, before preparation.
* Player listener: discover its interface, error callback, and original delegate from the
  error wrapper containing `Error occurred when playing the media.` and the capability
  downgrade message. Generate a concrete adapter implementing every callback. The error
  callback reads the discovered PlaybackException integer code and catches `Throwable`.
* Video renderer: `MediaCodecVideoRenderer` / `Video codec error` identifies its class.
  Parameter and return shapes locate supports-format, format configuration, and the
  `(String, long, long)` successful decoder initialization callback.
* Format formatter: `id=`, `, mimeType=`, `, codecs=`, `, res=`, `, fps=`. Invoke the app's
  formatter from a generated typed bridge. No runtime reflection or obfuscated names.
* Decoder queue: `Failed to initialize decoder: ` and
  `Format exceeds selected codec's capabilities [%s, %s]`. Follow the `List.isEmpty`,
  `ArrayDeque` field write, and first `ArrayDeque.add(Object)` sequence, preserving the
  original retry loop and its cleanup. Unexpected shapes fail patching.

## Diagnostics and limits

`DiagLog` area `media` records listener attachment and policy, input MIME/codecs/resolution/
frame rate, raw renderer support and `capabilitiesExceeded`, available decoder names,
successful decoder name, each codec failure, and player error code name/number, message,
capability status, and complete cause/stack chain. Media3 decoder exception messages carry
the failing codec name; Android codec exceptions also contribute `getDiagnosticInfo()`.
Unspecified format values are omitted by Media3's formatter. Non-renderer errors report
capability status as unknown. Numeric error names follow the AndroidX
[PlaybackException definitions](https://github.com/androidx/media/blob/release/libraries/common/src/main/java/androidx/media3/common/PlaybackException.java).

This is a best-effort decoder attempt, not proof that Snapdragon 480 can decode 4K.
Hardware may reject configuration, and software decoders can be absent, reject the format,
or be too slow. Fallback covers initialization failures; a decoder that initializes and
later fails during playback is reported, not automatically replaced. Phone playback,
device performance, and in-app diagnostics cannot be verified in this workspace.

## Verification

The patch was applied to the original versionCode 2081362 APK: all fingerprints resolved,
the generated listener implements the discovered Player.Listener interface, and all four
DEX files serialized successfully. Hook tests covered preference ON/OFF, decoder ordering,
empty decoder lists, failure restoration, capability recovery gating, unchanged support
verdicts, and named errors/decoder cause chains. These checks do not execute Android playback.

The Windows sandbox rejects Java `Path.toRealPath()` on the installed SDK jars even though
they are readable. Compilation was checked using an identical SDK copy under the project's
ignored build directory; no SDK or Media3 dependencies were changed in the project.
