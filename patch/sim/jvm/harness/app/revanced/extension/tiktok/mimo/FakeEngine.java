package app.revanced.extension.tiktok.mimo;

import android.os.Clock;

import java.util.ArrayList;
import java.util.List;

/** Модель TTVideoEngine — та же, что Player в autoskip_sim.py. Методы названы как у настоящего движка. */
public class FakeEngine {
    public interface SeekCompletionListener { void onCompletion(boolean success); }

    final long dur; final double speed; final long lag;
    double basePos; long baseT;
    long pendAt = -1; double pendPos; SeekCompletionListener pendListener;
    int positionCalls;
    final List<long[]> ownJumps = new ArrayList<>();     // {время, откуда, куда}

    FakeEngine(long dur, double speed, long lag) { this.dur = dur; this.speed = speed; this.lag = lag; }

    double posAt(long t) {
        if (pendAt >= 0 && t >= pendAt) {
            basePos = pendPos; baseT = pendAt; pendAt = -1;
            SeekCompletionListener l = pendListener; pendListener = null;
            if (l != null) l.onCompletion(true);
        }
        return (basePos + (t - baseT) * speed) % dur;
    }

    public int getCurrentPlaybackTime() { positionCalls++; return (int) posAt(Clock.now); }
    public int getDuration() { return (int) dur; }
    public void play() { }
    public void pause() { }

    /** Пропатченный seekTo: первая инструкция — хук Мимо, как вставляет MimoPatch.kt. */
    public void seekTo(int ms, SeekCompletionListener listener) {
        SponsorManager.onEngineSeek(this, ms);
        boolean own = SponsorManager.Player.ownCall;
        double p = posAt(Clock.now);
        if (own) ownJumps.add(new long[]{Clock.now, (long) p, ms});
        basePos = p; baseT = Clock.now;
        pendAt = Clock.now + (own ? lag : 0);              // перемотка человека — сразу, как в симуляции
        pendPos = ms; pendListener = listener;
    }
}
