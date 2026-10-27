package app.revanced.extension.tiktok.mimo;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Utils;

/**
 * Автопропуск. Вызывается из хуков, вся работа — на главном потоке.
 *
 * Таймлайн не опрашивается непрерывно: следующая проверка ставится на половину
 * расстояния до ближайшего отрезка (бисекция) — далеко от рекламы это раз в секунду,
 * у самой границы — раз в кадр. В симуляции на 10 000 сценариев — 1,6 вызова
 * getCurrentPlaybackTime() в секунду (медиана) против 60 при опросе каждый кадр.
 */
public final class SponsorManager {
    private static final String TAG = "Mimo";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Алгоритм проверен симуляцией patch/sim/autoskip_sim.py (10 000 сценариев) — правки делать в обоих местах.
    private static final long FAR_TICK_MS = 1000;      // реже не надо: перемотка руками должна замечаться
    private static final long MIN_TICK_MS = 16;        // один кадр при 60 Гц
    private static final long END_GUARD_MS = 250;      // реклама до конца: прыгаем почти в конец, ролик сам уйдёт в повтор
    private static final long MIN_JUMP_MS = 400;       // прыжок короче — дёрганье, а не пропуск
    private static final double MAX_COVERAGE = 0.8;    // отрезки на 80% ролика — что-то не так, не трогаем совсем
    private static final int LOOP_GUARD = 3;           // 3 прыжка подряд почти без ролика между ними — это петля
    private static final long LOOP_PROGRESS_MS = 300;  // «почти без ролика»
    private static final long SEEK_SETTLE_MS = 1000;   // дольше перемотку не ждём
    private static final long NEAR_MS = 300;           // перемотка человека «доехала» — позиция рядом с целью

    private static WeakReference<Object> engine = new WeakReference<>(null);
    private static String videoId;
    private static List<Segment> segments = Collections.emptyList();
    private static final Set<String> doneThisLoop = new HashSet<>();
    private static long lastPos = -1;
    private static long lastTickAt;
    private static boolean disabled;
    private static boolean coverageChecked;            // длина ролика известна только от плеера — проверяем, как только она есть
    private static long landing = -1;                  // куда приземлились прошлым прыжком
    private static int streak;

    // Перемотка в полёте: пока плеер её не применил, getCurrentPlaybackTime() отдаёт старую позицию.
    private static long seekTarget = -1;
    private static long seekUntil;
    private static Boolean seekLanded;                 // своя: ждём SeekCompletionListener; чужая (null): по позиции
    private static long userTarget = -1;               // человек перемотал сам (хук TTVideoEngine.seekTo)

    static Segment lastSkipped;

    // ---- хуки ---------------------------------------------------------------------------

    /** BaseListFragmentPanel.onRenderFirstFrame: в ленте первый кадр ролика. */
    public static void onVideoChanged(Object aweme) {
        try {
            final String id = Ids.of(aweme);
            MAIN.post(() -> switchTo(id));
        } catch (Throwable t) {
            Log.e(TAG, "onVideoChanged", t);
        }
    }

    /** TTVideoEngine.play(): этот движок сейчас и играет. Может прийти не с главного потока. */
    public static void onEnginePlay(Object e) {
        MAIN.post(() -> {
            engine = new WeakReference<>(e);
            schedule(0);
        });
    }

    /**
     * TTVideoEngine.seekTo(int, …): перемотка. Свою узнаём по флагу (её вызываем мы сами,
     * синхронно, с главного потока), всё остальное — человек тянет полосу или TikTok сам.
     */
    public static void onEngineSeek(Object e, int ms) {
        if (Player.ownCall && Looper.myLooper() == Looper.getMainLooper()) return;
        MAIN.post(() -> {
            if (e != engine.get()) return;
            userTarget = ms;
            schedule(0);
        });
    }

    /** SeekCompletionListener нашей перемотки. */
    static void onSeekComplete() {
        MAIN.post(() -> { if (seekLanded != null) seekLanded = true; });
    }

    /** FeedApiService.fetchFeedList → FeedItemList: тянем таймкоды на всю порцию заранее. */
    public static void onFeed(Object feedItemList) {
        try {
            Field f = feedItemList.getClass().getField("items");
            List<?> items = (List<?>) f.get(feedItemList);
            if (items == null) return;
            List<String> ids = new ArrayList<>();
            for (Object aweme : items) {
                String id = Ids.of(aweme);
                if (id != null) ids.add(id);
            }
            MimoApi.prefetch(ids);
        } catch (Throwable t) {
            Log.w(TAG, "onFeed: " + t);
        }
    }

    // ---- состояние ----------------------------------------------------------------------

    static String currentVideo() { return videoId; }

    private static void switchTo(String id) {
        if (id == null || id.equals(videoId)) return;   // тот же ролик вернулся на экран — ничего не сбрасываем
        videoId = id;
        segments = Collections.emptyList();
        doneThisLoop.clear();
        lastPos = -1;
        disabled = false;
        coverageChecked = false;
        landing = -1;
        streak = 0;
        seekTarget = -1;
        userTarget = -1;
        lastSkipped = null;
        MimoButtons.onVideoChanged(id);
        MimoApi.segments(id, list -> {
            if (!id.equals(videoId)) return;            // пока шёл ответ, уже листнули дальше
            segments = sorted(list);
            schedule(0);
        });
    }

    private static List<Segment> sorted(List<Segment> list) {
        List<Segment> s = new ArrayList<>(list);
        Collections.sort(s, (a, b) -> Long.compare(a.startMs, b.startMs));
        return s;
    }

    private static long coverage(List<Segment> list) {
        long total = 0, reach = -1;
        for (Segment s : list) {
            long from = Math.max(s.startMs, reach);
            if (s.endMs > from) total += s.endMs - from;
            reach = Math.max(reach, s.endMs);
        }
        return total;
    }

    // ---- планировщик --------------------------------------------------------------------

    private static final Runnable TICK = SponsorManager::tick;

    private static void schedule(long delay) {
        MAIN.removeCallbacks(TICK);
        if (!segments.isEmpty() && !disabled) MAIN.postDelayed(TICK, delay);
    }

    private static void tick() {
        Object e = engine.get();
        long pos = Player.position(e);
        if (pos < 0) { schedule(FAR_TICK_MS); return; }
        long now = SystemClock.uptimeMillis();
        long dur = Player.duration(e);
        if (!coverageChecked && dur > 0) {
            coverageChecked = true;
            if (coverage(segments) > dur * MAX_COVERAGE) {
                disabled = true;
                Log.w(TAG, "отрезки покрывают почти весь ролик " + videoId + " — не пропускаем");
                return;
            }
        }

        if (userTarget >= 0) {
            // человек перемотал сам: куда попал — то и хочет смотреть, даже рекламу
            long to = userTarget;
            userTarget = -1;
            if (to < lastPos) doneThisLoop.clear();
            for (Segment s : segments) if (s.contains(to)) doneThisLoop.add(s.uuid);
            // TikTok перематывает со своим слушателем — о завершении нам не скажут, ждём по позиции
            seekTarget = to;
            seekUntil = now + SEEK_SETTLE_MS;
            seekLanded = null;
        }
        if (seekTarget >= 0) {
            boolean landed = seekLanded == null ? Math.abs(pos - seekTarget) <= NEAR_MS : seekLanded;
            if (!landed && now < seekUntil) { schedule(MIN_TICK_MS); return; }   // плеер ещё отдаёт старую позицию
            seekTarget = -1;
            lastPos = pos;
            lastTickAt = now;
        }

        if (lastPos >= 0) {
            long elapsed = now - lastTickAt;
            boolean backwards = pos + 1500 < lastPos;
            boolean naturalLoop = backwards && isNaturalLoop(pos, lastPos, elapsed, dur);
            boolean userJump = !naturalLoop && (backwards || pos - lastPos > elapsed * 2 + 400);
            if (naturalLoop) {
                doneThisLoop.clear();                   // новый круг — пропускаем заново
            } else if (userJump) {
                // запасной путь, если хук перемотки не сработал: попал в рекламу руками — не мешаем
                if (backwards) doneThisLoop.clear();
                for (Segment s : segments) if (s.contains(pos)) doneThisLoop.add(s.uuid);
            }
        }
        lastPos = pos;
        lastTickAt = now;

        Segment next = null;
        for (Segment s : segments) {
            if (doneThisLoop.contains(s.uuid)) continue;
            if (s.contains(pos)) { skip(e, s, pos, dur, now); return; }
            if (s.startMs > pos && next == null) next = s;
        }
        schedule(nextDelay(pos, next, dur));
    }

    /** За прошедшее время (до ×3 скорости) плеер мог доиграть хвост и сам уйти на новый круг. */
    private static boolean isNaturalLoop(long pos, long last, long elapsed, long dur) {
        long run = elapsed * 3 + 500;
        return dur > 0 && last > dur - run && pos < run;
    }

    /** Половина расстояния до ближайшего отрезка. Впереди на этом круге пусто — целимся через повтор в первый. */
    private static long nextDelay(long pos, Segment next, long dur) {
        long gap;
        if (next != null) gap = next.startMs - pos;
        else if (dur > 0 && !segments.isEmpty()) gap = dur - pos + segments.get(0).startMs;
        else return FAR_TICK_MS;
        return Math.max(MIN_TICK_MS, Math.min(FAR_TICK_MS, gap / 2));
    }

    private static void skip(Object e, Segment first, long pos, long dur, long now) {
        // цепочка соседних отрезков — одним прыжком, без мигания между ними
        long target = first.endMs;
        doneThisLoop.add(first.uuid);
        for (Segment s : segments) {
            if (s.startMs <= target + 300 && s.endMs > target) { target = s.endMs; doneThisLoop.add(s.uuid); }
        }
        if (dur > 0) target = Math.min(target, dur - END_GUARD_MS);
        if (target - pos < MIN_JUMP_MS) { schedule(FAR_TICK_MS); return; }

        if (landing >= 0) {
            long played = pos >= landing ? pos - landing : dur - landing + pos;
            streak = played < LOOP_PROGRESS_MS ? streak + 1 : 0;
            if (streak >= LOOP_GUARD) {
                disabled = true;
                Log.w(TAG, "прыжок за прыжком без ролика между ними — выключаю пропуск на этом ролике");
                return;
            }
        }
        landing = target;

        if (Player.seekTo(e, target)) {
            seekTarget = target;
            seekUntil = now + SEEK_SETTLE_MS;
            seekLanded = false;
            lastPos = target;
            lastTickAt = now;
            lastSkipped = first;
            MimoButtons.onSkipped(first);
            // экран чистый: ни плашек, ни вибрации — только тихий счётчик для окна статистики
            Stats.skipped(target - pos);
        }
        schedule(MIN_TICK_MS);
    }

    /** Кнопка «↩» сразу после пропуска: вернуться и досмотреть (и, может быть, сказать «это не реклама»). */
    static void undoSkip() {
        Segment s = lastSkipped;
        if (s == null) return;
        doneThisLoop.add(s.uuid);
        long now = SystemClock.uptimeMillis();
        if (Player.seekTo(engine.get(), s.startMs)) {
            seekTarget = s.startMs;
            seekUntil = now + SEEK_SETTLE_MS;
            seekLanded = false;
        }
        lastPos = s.startMs;
        lastTickAt = now;
        landing = -1;
        lastSkipped = null;
        schedule(MIN_TICK_MS);
    }

    static long position() { return Player.position(engine.get()); }
    static long duration() { return Player.duration(engine.get()); }
    static void pause() { Player.call(engine.get(), "pause"); }
    static void resume() { Player.call(engine.get(), "play"); }

    static void toast(String text) {
        try {
            Toast.makeText(Utils.getContext(), text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    // ---- ID ролика ----------------------------------------------------------------------

    /** aweme_id из модели ролика: getAid() → поле aid → ссылка «поделиться». */
    static final class Ids {
        private static final Pattern VIDEO = Pattern.compile("/video/(\\d{8,20})");
        private static Method getAid;
        private static boolean noGetAid;

        static String of(Object aweme) {
            if (aweme == null) return null;
            try {
                if (!noGetAid) {
                    if (getAid == null) getAid = aweme.getClass().getMethod("getAid");
                    Object v = getAid.invoke(aweme);
                    if (v != null && v.toString().matches("\\d{8,20}")) return v.toString();
                }
            } catch (NoSuchMethodException e) {
                noGetAid = true;
            } catch (Throwable ignored) { }
            try {
                Field f = aweme.getClass().getField("aid");
                Object v = f.get(aweme);
                if (v != null && v.toString().matches("\\d{8,20}")) return v.toString();
            } catch (Throwable ignored) { }
            try {
                Object url = aweme.getClass().getMethod("getShareUrl").invoke(aweme);
                Matcher m = VIDEO.matcher(String.valueOf(url));
                if (m.find()) return m.group(1);
            } catch (Throwable ignored) { }
            return null;
        }
    }

    // ---- мост к TTVideoEngine -----------------------------------------------------------

    /**
     * Методы движка берём рефлексией и кэшируем: если в новой версии TikTok сигнатура
     * поменяется (int → long, другой слушатель), мод не падает, а тихо выключает пропуск.
     */
    static final class Player {
        private static Class<?> cls;
        private static Method pos, dur, seek;
        private static Object seekListener;
        private static boolean broken;
        static boolean ownCall;         // идёт наш собственный seekTo — хук перемотки его пропускает

        private static boolean bind(Object e) {
            if (e == null || broken) return false;
            if (cls == e.getClass()) return true;
            try {
                Class<?> c = e.getClass();
                pos = find(c, 0, "getCurrentPlaybackTime", "LJFF", "LJI");
                dur = find(c, 0, "getDuration", "LJI", "LJFF");
                try {
                    seek = find(c, 2, "seekTo", "LJJIJIIJI");
                    Class<?> listener = seek.getParameterTypes()[1];
                    seekListener = listener.isInterface()
                            ? Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                                (proxy, m, args) -> {
                                    if (m.getDeclaringClass() == Object.class) {      // equals/hashCode/toString
                                        return m.getName().equals("equals") ? proxy == args[0]
                                                : m.getName().equals("hashCode") ? System.identityHashCode(proxy) : "MimoSeek";
                                    }
                                    onSeekComplete();
                                    return defaultOf(m.getReturnType());
                                })
                            : null;
                } catch (NoSuchMethodException ex) {
                    seek = find(c, 1, "seekTo", "LJJ");
                    seekListener = null;
                }
                cls = c;
                return true;
            } catch (Throwable t) {
                broken = true;
                Log.e(TAG, "TTVideoEngine не похож на ожидаемый — пропуск выключен", t);
                return false;
            }
        }

        private static Method find(Class<?> c, int params, String... names) throws NoSuchMethodException {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    for (String name : names) {
                        if (m.getName().equals(name) && m.getParameterTypes().length == params
                                && (params == 0 || m.getParameterTypes()[0] == int.class)) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
                for (Class<?> iface : k.getInterfaces()) {
                    for (Method m : iface.getDeclaredMethods()) {
                        for (String name : names) {
                            if (m.getName().equals(name) && m.getParameterTypes().length == params
                                    && (params == 0 || m.getParameterTypes()[0] == int.class)) {
                                m.setAccessible(true);
                                return m;
                            }
                        }
                    }
                }
            }
            throw new NoSuchMethodException(java.util.Arrays.toString(names));
        }

        private static Object defaultOf(Class<?> t) {
            if (t == boolean.class) return false;
            if (t == void.class || !t.isPrimitive()) return null;
            return 0;
        }

        static long position(Object e) {
            try { return bind(e) ? ((Number) pos.invoke(e)).longValue() : -1; } catch (Throwable t) { return -1; }
        }

        static long duration(Object e) {
            try { return bind(e) ? ((Number) dur.invoke(e)).longValue() : -1; } catch (Throwable t) { return -1; }
        }

        static boolean seekTo(Object e, long ms) {
            try {
                if (!bind(e)) return false;
                ownCall = true;
                try {
                    if (seek.getParameterTypes().length == 2) {
                        seek.invoke(e, (int) ms, seekListener);
                    } else {
                        seek.invoke(e, (int) ms);
                    }
                } finally {
                    ownCall = false;
                }
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "seekTo", t);
                return false;
            }
        }

        static void call(Object e, String name) {
            try {
                if (bind(e)) find(e.getClass(), 0, name).invoke(e);
            } catch (Throwable t) {
                Log.w(TAG, name + ": " + t);
            }
        }
    }
}
