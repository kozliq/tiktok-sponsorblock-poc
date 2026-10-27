package android.os;

public class Handler {
    public Handler(Looper l) { }
    public final boolean post(Runnable r) { return postDelayed(r, 0); }
    public final boolean postDelayed(Runnable r, long delay) {
        Clock.QUEUE.add(new Clock.Task(Clock.now + Math.max(0, delay), r));
        return true;
    }
    public final void removeCallbacks(Runnable r) { Clock.QUEUE.removeIf(t -> t.r == r); }
}
