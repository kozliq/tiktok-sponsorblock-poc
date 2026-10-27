package app.revanced.extension.tiktok.mimo;

import android.os.Clock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Прогон НАСТОЯЩЕГО SponsorManager.java на сценариях из autoskip_sim.py (их пишет scenarios.py).
 * Свойства и допуски — те же, что в симуляции.
 */
public final class Harness {
    static final int STEP = 2, MIN_TICK = 16, END_GUARD = 250, MIN_JUMP = 400;

    public static void main(String[] args) throws Exception {
        Map<String, Object> cache = cache();
        Constructor<?> cached = Class.forName("app.revanced.extension.tiktok.mimo.MimoApi$Cached").getDeclaredConstructor(List.class);
        cached.setAccessible(true);
        Field disabledF = SponsorManager.class.getDeclaredField("disabled");
        disabledF.setAccessible(true);

        int total = 0, bad = 0, disabledCoverage = 0;
        List<Double> rates = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new FileReader(args[0]))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] part = line.split("\\|", -1);
                String[] head = part[0].trim().split(" ");
                int n = Integer.parseInt(head[0]);
                long dur = Long.parseLong(head[1]);
                double speed = Double.parseDouble(head[2]);
                long lag = Long.parseLong(head[3]);
                List<long[]> segs = new ArrayList<>();
                List<Segment> list = new ArrayList<>();
                for (String s : part[1].trim().split(";")) {
                    if (s.isEmpty()) continue;
                    String[] f = s.split(",");
                    long a = Long.parseLong(f[0]), b = Long.parseLong(f[1]);
                    segs.add(new long[]{a, b, segs.size()});
                    list.add(new Segment(f[2], a, b, "scam", 1));
                }
                List<long[]> seeks = new ArrayList<>();
                for (String s : part[2].trim().split(";")) {
                    if (s.isEmpty()) continue;
                    String[] f = s.split(",");
                    seeks.add(new long[]{Long.parseLong(f[0]), Long.parseLong(f[1])});
                }

                // --- новый ролик ---
                Clock.reset();
                android.widget.Toast.shown = 0;
                String id = String.valueOf(7300000000000000000L + n);
                cache.put(id, cached.newInstance(list));
                FakeEngine engine = new FakeEngine(dur, speed, lag);
                SponsorManager.onVideoChanged(new FakeAweme(id));
                SponsorManager.onEnginePlay(engine);

                long totalT = (long) (3 * dur / speed);
                List<String> problems = new ArrayList<>();
                Map<Integer, Double> exposure = new HashMap<>();
                Set<Integer> userIn = new HashSet<>();
                int si = 0;
                double prev = 0;
                for (long t = 0; t < totalT; t += STEP) {
                    boolean justSeeked = false;
                    if (si < seeks.size() && t >= seeks.get(si)[0]) {
                        long to = seeks.get(si++)[1];
                        Clock.runUntil(t);
                        engine.seekTo((int) to, null);          // человек тянет полосу
                        justSeeked = true;
                        userIn.clear();
                        for (long[] s : segs) if (s[0] <= to && to < s[1]) userIn.add((int) s[2]);
                    }
                    Clock.runUntil(t);
                    double pos = engine.posAt(t);
                    if (pos + 1000 < prev && prev > dur - 1000 && pos < 1000 && !justSeeked) {
                        userIn.clear();
                        exposure.clear();
                    }
                    prev = pos;
                    boolean disabled = disabledF.getBoolean(null);
                    for (long[] s : segs) {
                        int u = (int) s[2];
                        if (s[0] <= pos && pos < s[1] && !userIn.contains(u) && !disabled) {
                            exposure.merge(u, STEP * speed, Double::sum);
                        } else if (exposure.getOrDefault(u, 0.0) > 0) {
                            exposure.put(u, 0.0);
                        }
                        if (userIn.contains(u) || disabled) continue;
                        double seen = exposure.getOrDefault(u, 0.0);
                        double allowed = MIN_TICK * 2 * speed + lag * speed + 4 * STEP * speed + 2;
                        if (s[1] >= dur - END_GUARD - MIN_JUMP) allowed += END_GUARD + MIN_JUMP;
                        if (s[1] - s[0] < MIN_JUMP + 50) allowed = s[1] - s[0];
                        if (seen > allowed + 1) {
                            problems.add("реклама " + Arrays.toString(s) + " видна " + Math.round(seen) + " мс при допуске " + Math.round(allowed));
                            exposure.put(u, -1e9);
                        }
                    }
                }
                for (int i = 0; i < seeks.size(); i++) {
                    long ut = seeks.get(i)[0], uto = seeks.get(i)[1];
                    long[] inside = null;
                    for (long[] s : segs) if (s[0] <= uto && uto < s[1]) { inside = s; break; }
                    if (inside == null) continue;
                    double until = ut + (inside[1] - uto) / speed - 50;
                    if (i + 1 < seeks.size()) until = Math.min(until, seeks.get(i + 1)[0]);
                    for (long[] j : engine.ownJumps) {
                        if (ut < j[0] && j[0] < until && inside[0] <= j[1] && j[1] < inside[1]) {
                            problems.add("выдернул из рекламы, куда человек перемотал сам (" + uto + ")");
                            break;
                        }
                    }
                }
                if (android.widget.Toast.shown > 0 && !engine.ownJumps.isEmpty())
                    problems.add("при автопропуске всплыла плашка (" + android.widget.Toast.shown + ")");
                boolean disabled = disabledF.getBoolean(null);
                if (disabled) {
                    if (coverage(segs) > dur * 0.8) disabledCoverage++;
                    else problems.add("пропуск выключен защитой от петли в нормальном сценарии");
                }
                rates.add(engine.positionCalls / (totalT / 1000.0));
                total++;
                if (!problems.isEmpty()) {
                    bad++;
                    if (bad <= 10) System.out.println("✘ #" + n + ": " + problems.get(0) + "  " + line);
                }
            }
        }
        rates.sort(Double::compare);
        System.out.printf("%nНастоящий SponsorManager.java: сценариев %d, без ошибок %d, с ошибками %d%n", total, total - bad, bad);
        System.out.printf("Проверок таймлайна в секунду: медиана %.1f, максимум %.1f; выключался по покрытию 80%%+: %d%n",
                rates.get(rates.size() / 2), rates.get(rates.size() - 1), disabledCoverage);
        System.exit(bad == 0 ? 0 : 1);
    }

    static long coverage(List<long[]> segs) {
        List<long[]> s = new ArrayList<>(segs);
        s.sort((a, b) -> Long.compare(a[0], b[0]));
        long total = 0, reach = -1;
        for (long[] x : s) { long from = Math.max(x[0], reach); if (x[1] > from) total += x[1] - from; reach = Math.max(reach, x[1]); }
        return total;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cache() throws Exception {
        Field f = MimoApi.class.getDeclaredField("CACHE");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(null);
    }
}
