package app.revanced.extension.tiktok.mimo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.revanced.extension.shared.Utils;

/**
 * Сеть и кэш. Приватность как у SponsorBlock: серверу уходит не ID ролика, а первые
 * 4 hex-символа его sha256 (65 536 корзин) — сервер отдаёт все ролики корзины, а нужный
 * мы находим у себя по полному хэшу. Какой именно ролик смотрит человек, сервер не знает.
 */
public final class MimoApi {
    private static final String TAG = "Mimo";
    private static final String API = "https://mimo.flownote.cc";
    private static final int PREFIX = 4;
    private static final long TTL_MS = 15 * 60_000;
    private static final int CACHE_SIZE = 400;

    public interface SegmentsCallback { void done(List<Segment> segments); }
    public interface ResultCallback { void done(boolean ok, String message); }

    private static final ExecutorService NET = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final class Cached {
        final List<Segment> segments; final long at;
        Cached(List<Segment> s) { segments = s; at = System.currentTimeMillis(); }
    }

    /** id ролика → отрезки. Пустой список — тоже ответ: «рекламы нет», повторно не спрашиваем. */
    private static final Map<String, Cached> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Cached>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> e) { return size() > CACHE_SIZE; }
            });
    /** Запросы в полёте по префиксу: десять роликов одной корзины — один запрос. */
    private static final Map<String, List<Runnable>> IN_FLIGHT = new HashMap<>();
    /** Какие ролики ждут ответа по префиксу: кого не окажется в ответе, запомним как «без рекламы». */
    private static final Map<String, List<String>> WANTED = new HashMap<>();

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Отрезки из кэша или null, если ещё не знаем. Только чтение, без сети. */
    static List<Segment> cached(String id) {
        Cached e = CACHE.get(id);
        return e != null && System.currentTimeMillis() - e.at < TTL_MS ? e.segments : null;
    }

    /** Отрезки ролика; callback — на главном потоке. Без сети отдаём пустой список: лента важнее. */
    static void segments(String id, SegmentsCallback cb) {
        List<Segment> hit = cached(id);
        if (hit != null) { cb.done(hit); return; }
        String hash = sha256(id);
        String prefix = hash.substring(0, PREFIX);
        Runnable deliver = () -> {
            List<Segment> got = cached(id);
            MAIN.post(() -> cb.done(got != null ? got : Collections.emptyList()));
        };
        synchronized (IN_FLIGHT) {
            List<Runnable> waiting = IN_FLIGHT.get(prefix);
            if (waiting != null) {
                waiting.add(deliver);
                WANTED.get(prefix).add(id);
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(deliver);
            IN_FLIGHT.put(prefix, waiting);
            List<String> ids = new ArrayList<>();
            ids.add(id);
            WANTED.put(prefix, ids);
        }
        NET.execute(() -> {
            List<Runnable> waiting;
            try {
                JSONArray videos = new JSONArray(request("GET", "/api/skip/v1/" + prefix, null));
                List<String> ids;
                synchronized (IN_FLIGHT) { ids = new ArrayList<>(WANTED.get(prefix)); }
                fill(videos, ids);
            } catch (Exception e) {
                Log.w(TAG, "segments: " + e);
            } finally {
                synchronized (IN_FLIGHT) {
                    waiting = IN_FLIGHT.remove(prefix);
                    WANTED.remove(prefix);
                }
            }
            if (waiting != null) for (Runnable r : waiting) r.run();
        });
    }

    /** Порция ленты: тянем всё заранее. */
    static void prefetch(List<String> ids) {
        for (String id : ids) if (cached(id) == null) segments(id, s -> { });
    }

    private static void fill(JSONArray videos, List<String> wanted) throws Exception {
        Map<String, List<Segment>> byHash = new HashMap<>();
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            JSONArray segs = v.getJSONArray("segments");
            List<Segment> list = new ArrayList<>();
            for (int j = 0; j < segs.length(); j++) {
                JSONObject s = segs.getJSONObject(j);
                JSONArray t = s.getJSONArray("segment");
                list.add(new Segment(s.getString("UUID"), t.getLong(0), t.getLong(1),
                        s.optString("category", "sponsor"), s.optInt("votes")));
            }
            byHash.put(v.getString("hash"), list);
            CACHE.put(v.getString("videoID"), new Cached(list));
        }
        for (String id : wanted) {
            if (!byHash.containsKey(sha256(id))) CACHE.put(id, new Cached(Collections.emptyList()));
        }
    }

    static void submit(String videoId, long startMs, long endMs, long durationMs, String category, ResultCallback cb) {
        NET.execute(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("videoID", videoId).put("userID", userId())
                        .put("startMs", startMs).put("endMs", endMs)
                        .put("durationMs", durationMs).put("category", category);
                JSONObject r = new JSONObject(request("POST", "/api/segments", body.toString()));
                CACHE.remove(videoId);
                String msg = r.optBoolean("merged")
                        ? "Такую рекламу уже отмечали — засчитал как подтверждение"
                        : "Отметил! Когда подтвердят другие, её будут пропускать у всех";
                MAIN.post(() -> cb.done(true, msg));
            } catch (ApiError e) {
                MAIN.post(() -> cb.done(false, e.getMessage()));
            } catch (Exception e) {
                MAIN.post(() -> cb.done(false, "База сейчас не отвечает"));
            }
        });
    }

    /** type: 1 — «да, реклама», 0 — «это не реклама». */
    static void vote(String uuid, int type) {
        NET.execute(() -> {
            try {
                request("POST", "/api/vote", new JSONObject()
                        .put("UUID", uuid).put("userID", userId()).put("type", type).toString());
            } catch (Exception e) {
                Log.w(TAG, "vote: " + e);
            }
        });
    }

    static final class ApiError extends Exception {
        private static final long serialVersionUID = 1L;
        ApiError(String m) { super(m); }
    }

    private static String request(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("User-Agent", "mimo-tiktok/1");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String reason = "";
                try { reason = new JSONObject(text).optString("error"); } catch (Exception ignored) { }
                throw new ApiError(reason.isEmpty() ? "Ошибка базы " + code : reason);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0 && out.size() < 512_000) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Случайный ID этого телефона: не привязан ни к аккаунту TikTok, ни к устройству. */
    private static synchronized String userId() {
        Context ctx = Utils.getContext();
        SharedPreferences p = ctx.getSharedPreferences("mimo", Context.MODE_PRIVATE);
        String id = p.getString("user_id", null);
        if (id == null) {
            byte[] r = new byte[16];
            new SecureRandom().nextBytes(r);
            StringBuilder b = new StringBuilder();
            for (byte x : r) b.append(String.format("%02x", x));
            id = b.toString();
            p.edit().putString("user_id", id).apply();
        }
        return id;
    }
}
