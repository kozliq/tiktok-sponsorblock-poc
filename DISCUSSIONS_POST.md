# Ready-to-copy ReVanced GitHub Discussion Post

**Target URL:** https://github.com/ReVanced/revanced-discussions/discussions/new?category=general
**Category:** General
**Title:** `[Research / PoC] Hooking TTVideoEngine in TikTok to skip in-stream sponsored segments`

---

## Post Body:

Hi everyone,

I did some research and built a working Proof of Concept (PoC) for skipping in-stream sponsored segments (mid-rolls, promo reads, gambling integrations) in TikTok for Android. 

Since in-stream ads are baked into the video stream, standard feed filters can't drop them. The only viable approach is hooking the playback engine (`TTVideoEngine`) and programmatically seeking over flagged timecodes.

I wanted to share the findings, hook signatures, and algorithms with the ReVanced community in case maintainers or patch authors want to adapt this upstream.

### 1. Key Findings & Fingerprints
Despite aggressive R8 passes, ByteDance's platform player (`com.ss.ttvideoengine.TTVideoEngine`) keeps stable type signatures for core playback methods:
* `play() -> void`
* `getCurrentPlaybackTime() -> int`
* `seekTo(int, SeekCompletionListener) -> void`

Using ReVanced Patcher's declarative matching:
```kotlin
// Playback start
internal val BytecodePatchContext.enginePlayMethod by gettingFirstMethodDeclaratively {
    name("play")
    definingClass("Lcom/ss/ttvideoengine/TTVideoEngine;")
    returnType("V")
    parameterTypes()
}

// Seek intercept (int ms + obfuscated listener interface)
internal val BytecodePatchContext.engineSeekMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ss/ttvideoengine/TTVideoEngine;")
    returnType("V")
    parameterTypes("I", "Lo6f/c0;")
}
```

**Smali Gotcha:** Using `invoke-static/range` is strictly required. R8 often places the `this` register (`p0`) above `v15`, which causes Dalvik/ART `VerifyError` on startup if standard `invoke-static` is used.

### 2. Performance: Adaptive Bisection Polling
Polling `getCurrentPlaybackTime()` at 60 Hz causes noticeable UI frame drops. I implemented an adaptive bisection scheduler:
`nextDelay = clamp(16ms, 1000ms, gap / 2)`
* Far from ads: polls once per 1000 ms.
* Near boundary: exponentially narrows down to 16 ms.
* Result: median polling dropped from 60 calls/sec to **1.6 calls/sec** with 0ms perceptible skip latency.

### 3. Privacy: 16-Bit k-Anonymity
Instead of sending plaintext video IDs, the client queries `GET /api/skip/v1/{prefix}` using the first 4 hex chars (16 bits) of `SHA-256(video_id)` across 65,536 buckets. The server returns all segments in that bucket, and the client filters locally. The backend never knows which specific video is being watched.

---

I'm not maintaining a standalone APK fork, but I wanted to make the reverse-engineering artifacts, signatures, and bisection logic open for ReVanced. 

All source code and documentation: https://github.com/kozliq/tiktok-sponsorblock-poc

Hope this is helpful for future TikTok patches!
