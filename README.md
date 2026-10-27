# Reverse Engineering TikTok on Android: Hooking TTVideoEngine to Skip In-Stream Ads with k-Anonymity

In short-form video platforms (TikTok, Reels, Shorts), creator monetization has largely shifted from official platform-served ads to in-stream sponsorships: creators physically splice 5–15-second sponsor reads (skin gambling sites, unregulated crypto lotteries, shady Telegram channels) directly into the video file.

For YouTube, this issue was solved by SponsorBlock. I wanted to see if a similar mechanism could be brought to TikTok on Android. In doing so, I had to deal with ByteDance's proprietary playback engine, aggressive R8 bytecode obfuscation, frame-timing overhead, and privacy concerns regarding crowdsourced timecodes.

This write-up covers the technical details of the resulting Proof of Concept (PoC): how TikTok's video engine is structured, how to reliably hook obfuscated player methods using declarative signatures in ReVanced, an adaptive bisection scheduler that minimizes JNI polling, and a $k$-anonymous backend architecture built on Cloudflare Workers.

---

## 1. Why Network Filters and Feed Blockers Fail

Ad blocking on mobile generally operates at two levels:

1. **DNS & Packet Filtering (AdGuard, Pi-hole):** Blocks tracking domains and ad-network endpoints. For in-stream video, this is completely useless: the sponsored segment is baked into the same continuous MP4 byte stream delivered by ByteDance's Akamai/Cloudflare CDN edges. Severing the connection kills the entire video.
2. **Feed Filtering (e.g., ReVanced Feed Filter):** Intercepts the `/aweme/v1/feed/` API response. Platform-inserted ads carry explicit flags like `"is_ads": true`, allowing client-side hooks to drop those items from `FeedItemList.items` before rendering.

In-stream mid-rolls bypass feed filtering entirely. To the client and platform backend, the video payload is indistinguishable from standard organic content:

```json
{
  "aweme_id": "7421892381928391200",
  "desc": "Best settings for #gaming",
  "is_ads": false,
  "video": {
    "play_addr": { "url_list": ["https://v16m-default.akamaized.net/..."] },
    "duration": 48200
  }
}
```

The only viable way to skip these baked-in sponsor segments without dropping the entire video is to intercept runtime playback and programmatically dispatch a `seekTo()` call at the boundary of the sponsored timecode.

---

## 2. Analyzing TTVideoEngine and Obfuscation

Unlike the majority of Android video apps that rely on Google’s `ExoPlayer` or the platform `MediaPlayer`, ByteDance uses its own proprietary media framework — **TTVideoEngine**, backed by native C++ libraries (`libttvideoengine.so` and `libmdl.so` for the Media Delivery Layer).

### DEX Layout

Modern TikTok APKs (v36.x+) are distributed as multi-DEX splits containing over 20 distinct DEX files:
* UI feed containers (`BaseListFragmentPanel`, `Aweme` data models) reside in `classes2.dex` through `classes5.dex`.
* The playback engine facade (`TTVideoEngine`) is buried deeper, typically in `classes16.dex` or adjacent splits.

ByteDance subjects the entire codebase to aggressive ProGuard/R8 passes:
* Class names are compressed into volatile short packages: `Lo6f/cO;`, `LX/0vR;`.
* Method and field identifiers are mangled: `LJJlJllJl()`, `LIZ()`, `LIZIZ()`.
* Minor app updates (e.g., `36.4.x` → `36.5.x`) regenerate symbol hashes, making static method-name patches immediately obsolete.

However, `TTVideoEngine` serves as an internal cross-product SDK across ByteDance's portfolio (it shares roots with the public Volcengine VEVodDemo). Because of this shared API surface, the canonical class name `com.ss.ttvideoengine.TTVideoEngine` remains stable, and several core playback primitives retain recognizable type signatures:
* `play() -> void`
* `getCurrentPlaybackTime() -> int` (playback position in milliseconds)
* `getDuration() -> int`
* `seekTo(int, SeekCompletionListener) -> void`

The complication lies in `SeekCompletionListener`. While single-argument `seekTo(int)` overloads are frequently renamed or stripped, the two-parameter overload retains a listener interface whose name is obfuscated (e.g., `Lo6f/c0;`).

---

## 3. Declarative Bytecode Fingerprints and Smali Hooking

To prevent the patch from breaking with each weekly TikTok update, I used declarative fingerprinting via ReVanced Patcher (`BytecodePatchContext`). Instead of matching volatile method names, the patcher matches methods by owner class, return types, and parameter type descriptors:

```kotlin
// Fingerprints.kt

// 1. Playback start: zero-parameter method returning void on TTVideoEngine
internal val BytecodePatchContext.enginePlayMethod by gettingFirstMethodDeclaratively {
    name("play")
    definingClass("Lcom/ss/ttvideoengine/TTVideoEngine;")
    returnType("V")
    parameterTypes()
}

// 2. Seek intercept: takes an int (ms) and an obfuscated listener interface
internal val BytecodePatchContext.engineSeekMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ss/ttvideoengine/TTVideoEngine;")
    returnType("V")
    parameterTypes("I", "Lo6f/c0;")
}
```

To validate these signatures prior to compiling an APK, I wrote a standalone static analysis script using `androguard` (`tools/find_hooks.py`) to verify target descriptors directly across the split DEX files:

```python
CHECKS = [
    ("player: TTVideoEngine.play()V", "Lcom/ss/ttvideoengine/TTVideoEngine;", "play", "()V"),
    ("player: getCurrentPlaybackTime()", "Lcom/ss/ttvideoengine/TTVideoEngine;", "getCurrentPlaybackTime", "()"),
    ("player: getDuration()", "Lcom/ss/ttvideoengine/TTVideoEngine;", "getDuration", "()"),
    ("player: seekTo(I, …)", "Lcom/ss/ttvideoengine/TTVideoEngine;", "seekTo", "(I"),
    ("feed:   getCurrentAweme()", "/BaseListFragmentPanel;", "getCurrentAweme", "()Lcom/ss/android/ugc/aweme/feed/model/Aweme;"),
]
```

### Smali Injection Details

In the bytecode patch (`MimoPatch.kt`), hooks forward engine references to our companion runtime manager (`SponsorManager`):

```kotlin
// Hook engine start event
enginePlayMethod.addInstruction(
    0,
    "invoke-static/range { p0 .. p0 }, Lapp/revanced/extension/tiktok/mimo/SponsorManager;->onEnginePlay(Ljava/lang/Object;)V"
)

// Hook user-initiated seeks: p1 contains target position in milliseconds
engineSeekMethod.addInstruction(
    0,
    "invoke-static/range { p0 .. p1 }, Lapp/revanced/extension/tiktok/mimo/SponsorManager;->onEngineSeek(Ljava/lang/Object;I)V"
)
```

**Implementation Gotcha:** Using `invoke-static/range` is strictly mandatory. Because R8 aggressively rearranges local register allocations, the `this` pointer (`p0`) often lands in registers higher than `v15`. A standard 4-bit `invoke-static` instruction targeting a high register will fail Dalvik/ART bytecode verification (`VerifyError`), instantly crashing the application on startup.

---

## 4. Adaptive Timeline Scheduling: Bisection vs. Polling

A naive implementation of a SponsorBlock client queries `getCurrentPlaybackTime()` on every display frame (every 16 ms for 60 Hz). However, executing repeated JNI transitions into native media code in a tight loop causes noticeable CPU wakeups, frame drops in the vertical feed, and thermal throttling.

To resolve this, I implemented an **adaptive bisection scheduler** in `SponsorManager.java`:

```java
/**
 * Calculates the delay until the next timeline check.
 * The interval is set to half the distance to the nearest target boundary.
 */
private static long nextDelay(long pos, Segment next, long dur) {
    long gap;
    if (next != null) {
        gap = next.startMs - pos;
    } else if (dur > 0 && !segments.isEmpty()) {
        // No segments left on current pass; target the first segment of the looped pass
        gap = dur - pos + segments.get(0).startMs;
    } else {
        return FAR_TICK_MS; // 1000 ms
    }
    return Math.max(MIN_TICK_MS, Math.min(FAR_TICK_MS, gap / 2));
}
```

* **Far from a sponsored segment:** The scheduler sleeps for `1000 ms`.
* **Approaching a segment:** The interval halves repeatedly (`gap / 2`), smoothly converging to a single frame (`16 ms`) right before the boundary.

In a benchmark simulation across 10,000 playback runs (`patch/sim/autoskip_sim.py`), adaptive bisection reduced the median number of `getCurrentPlaybackTime()` calls from **60 calls/sec down to 1.6 calls/sec**, with zero perceptible skip latency.

### Handling Edge Cases

1. **Segment Chaining:** If multiple segments overlap or sit within 300 ms of each other (e.g., an ad followed immediately by a subscribe callout), the engine chains them into a single `seekTo` operation to eliminate stutter.
2. **Respecting User Seeks:** If a user manually drags the scrubber directly into a sponsored segment, the `onEngineSeek` hook records the event and marks the segment as acknowledged (`doneThisLoop.add(s.uuid)`). The player will not fight the user by jumping out of a segment they intentionally sought into.
3. **Loop Guard:** If bad metadata flags over 80% of a video or causes 3 rapid consecutive jumps with less than 300 ms of actual playback between them, auto-skipping silently disables itself for that video ID.

---

## 5. Crowdsourcing Architecture: k-Anonymity and Cloudflare Workers

A community-driven skip database requires central storage. However, sending plaintext video IDs (`GET /api/segments?video_id=...`) leaks the user's complete viewing history and IP address to the server operator.

### 16-Bit Bucket Prefix k-Anonymity

To safeguard user privacy, I adapted the $k$-anonymity bucket model used by SponsorBlock:

```
[Android Client]                                              [Cloudflare Edge]
       │                                                              │
       ├─ Video ID: "7421892381928391200"                             │
       ├─ Hash: SHA-256(ID) = "a3f1b80c2e..."                         │
       ├─ Prefix: "a3f1" (First 16 bits / 4 hex chars)                │
       │                                                              │
       ├────────────── GET /api/skip/v1/a3f1 ─────────────────────────>
       │                                                              │
       │                                                Query D1 Database:
       │                                                WHERE hash LIKE 'a3f1%'
       │                                                + Cache API (Edge RAM)
       │                                                              │
       <────────────── HTTP 200: JSON [All segments in bucket a3f1] ──┤
       │                                                              │
       ├─ Local lookup: Match full SHA-256 against received array     │
       └─ Feed matching segments into player                          │
```

1. The client hashes the video ID with SHA-256.
2. Only the first 4 hexadecimal characters (16 bits) are transmitted over the wire: `GET /api/skip/v1/a3f1`.
3. The keyspace is divided into $2^{16} = 65,536$ discrete buckets.
4. The edge server returns all known segments belonging to that bucket.
5. The client filters the payload locally. The server never learns which specific video out of the bucket the user is actually watching.

### Edge Infrastructure on Cloudflare Workers + D1

The backend is built as an edge-native service:

* **Zero-Cold-Start Edge Caching:** Popular video buckets are served directly from data-center RAM using the Worker Cache API:
  ```typescript
  // worker/src/index.ts
  const cache = caches.default;
  const key = new Request(new URL(req.url).toString(), { method: "GET" });
  const hit = await cache.match(key);
  if (hit) return hit;
  ```
* **Reputation Without Identity:** Submitting users are identified solely by `HMAC-SHA256(userID, PEPPER)`, preventing correlation with IP logs.
* **Consensus Engine:** Submissions require net-positive community votes to become active. Segments with negative scores are automatically excluded from edge bucket responses.

---

## 6. Takeaways and Open-Source Handoff

This project was built as an exploratory **Proof of Concept (PoC)**. 

I do not intend to maintain or distribute a patched standalone TikTok APK. Maintaining a custom fork of a 400 MB proprietary application—dealing with anti-tampering heuristics, broken login flows, UI refactors, and regional variations—is an endless maintenance chore that has nothing to do with reverse engineering.

The core technical objectives of this research were achieved:
1. Showed that `TTVideoEngine` can be hooked via method type signatures on a non-obfuscated class instead of obfuscated method names. One parameter type (`Lo6f/c0;`, the seek listener) is still version-specific, so the hooks must be re-checked for every TikTok release with `patch/tools/find_hooks.py`.
2. Reduced playback polling overhead to 1.6 calls/sec using bisection.
3. Demonstrated that in-stream sponsor skipping can function with full $k$-anonymity at the edge.

All architectural concepts, Smali patterns, and signatures are open for the community to study. I hope these findings serve as a useful reference for the ReVanced maintainers or anyone exploring Android media internals.

---

### Key Technical Artifacts

* Player signature definitions: `app/revanced/patches/tiktok/mimo/Fingerprints.kt`
* Bisection & collision manager: `app/revanced/extension/tiktok/mimo/SponsorManager.java`
* Prefix-based API specification: `/api/skip/v1/{prefix}` (16-bit SHA-256)
* Segment consensus logic: `worker/src/consensus.ts`

### Repository Layout

```
patch/overlay/   ReVanced patch (Kotlin) + extension (Java) — copied on top of a revanced-patches checkout
patch/apply.sh   clones revanced-patches and drops the overlay in
patch/sim/       auto-skip simulation: Python model + harness that runs the real SponsorManager.java on the JVM
patch/tools/     find_hooks.py — checks every hook point against a given TikTok APK without building
worker/          Cloudflare Worker + D1: k-anonymous read API, submissions, votes, consensus, tests
```

### Reproduce the Numbers

```bash
python3 patch/sim/autoskip_sim.py 10000   # Python model of tick()/skip()
sh patch/sim/jvm/run.sh 10000             # the real SponsorManager.java (JDK 17 + Android SDK android-34)
cd worker && npm install && npm test      # consensus + API tests
```

Status: the patch builds into an APK for TikTok 36.5.4 (`com.zhiliaoapp.musically`). On-device field testing is in progress; the TikTok APK itself is not distributed here.
