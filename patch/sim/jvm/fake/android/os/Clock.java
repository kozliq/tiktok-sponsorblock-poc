package android.os;

import java.util.PriorityQueue;

/** Виртуальное время и очередь главного потока для прогона настоящего SponsorManager на JVM. */
public final class Clock {
    public static long now;
    private static long seq;

    static final class Task implements Comparable<Task> {
        final long at, order; final Runnable r;
        Task(long at, Runnable r) { this.at = at; this.r = r; this.order = seq++; }
        public int compareTo(Task o) { return at != o.at ? Long.compare(at, o.at) : Long.compare(order, o.order); }
    }

    static final PriorityQueue<Task> QUEUE = new PriorityQueue<>();

    /** Выполнить всё, что запланировано на момент до t включительно. */
    public static void runUntil(long t) {
        while (!QUEUE.isEmpty() && QUEUE.peek().at <= t) {
            Task k = QUEUE.poll();
            now = Math.max(now, k.at);
            k.r.run();
        }
        now = t;
    }

    public static void reset() { QUEUE.clear(); now = 0; }
}
