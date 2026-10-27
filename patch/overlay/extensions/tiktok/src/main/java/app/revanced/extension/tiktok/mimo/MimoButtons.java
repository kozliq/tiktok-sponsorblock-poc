package app.revanced.extension.tiktok.mimo;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Две круглые метки справа над колонкой «лайк / комментарии / поделиться»:
 *
 *   ✂  — разметка в два тапа: [IDLE] → тап 1 (T1, метка пульсирует красным) → [ARMED]
 *        → тап 2 (T2) → выбор «скам / спонсор / самопиар / интро» → POST /api/segments.
 *        Долгое нажатие в ARMED — отмена, в покое — окно личной статистики.
 *        Сам пропуск ничего не показывает и не вибрирует: ролик просто перепрыгивает рекламу. Сразу после автопропуска метка 4 с показывает «↩»:
 *        тап — вернуться к рекламе и, если это была не реклама, проголосовать «против».
 *   ⤓  — «Скачать чисто»: ролик уходит в бота Качалки, тот отдаёт его без водяного знака
 *        и с уже вырезанной рекламой.
 *
 * Вешаются поверх android.R.id.content, а не внутрь FeedActionButton: ресурсы и id той колонки
 * обфусцированы и меняются от версии к версии, а наш слой переживает любые её перестановки.
 */
public final class MimoButtons {
    private static final String TAG = "Mimo";
    private static final String BOT = "kachalka_videobot";
    private static final int RED = Color.rgb(0xE5, 0x39, 0x35);
    private static final int DIM = Color.argb(0x73, 0, 0, 0);
    private static final long UNDO_MS = 4000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private enum State { IDLE, ARMED, JUST_SKIPPED }

    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static TextView scissors, download;
    private static LinearLayout column;
    private static ObjectAnimator pulse;
    private static State state = State.IDLE;
    private static long markStart = -1;

    /** MainActivity.onCreate. */
    public static void attach(Activity a) {
        try {
            activity = new WeakReference<>(a);
            ViewGroup content = a.findViewById(android.R.id.content);
            if (content == null || content.findViewWithTag(TAG) != null) return;

            column = new LinearLayout(a);
            column.setTag(TAG);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setVisibility(View.GONE);

            scissors = circle(a, "✂");
            scissors.setContentDescription("Мимо: отметить рекламу");
            scissors.setOnClickListener(v -> onScissors());
            scissors.setOnLongClickListener(v -> {
                if (state == State.ARMED) cancel("Отметка отменена");
                else showStats();
                return true;
            });
            scissors.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            });
            download = circle(a, "⤓");
            download.setContentDescription("Скачать без водяного знака и без рекламы");
            download.setOnClickListener(v -> openKachalka());
            download.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            });

            column.addView(scissors);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44));
            gap.topMargin = dp(a, 14);
            column.addView(download, gap);

            column.setElevation(9999f);
            column.setZ(9999f);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.TOP);
            lp.rightMargin = dp(a, 12);
            // над аватаркой автора: колонка действий TikTok начинается примерно с 45% высоты
            lp.topMargin = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.25f);
            content.addView(column, lp);
            column.bringToFront();
        } catch (Throwable t) {
            Log.e(TAG, "attach", t);
        }
    }

    private static TextView circle(Activity a, String glyph) {
        TextView v = new TextView(a);
        v.setText(glyph);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        v.setGravity(Gravity.CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));
        v.setBackground(oval(DIM));
        v.setShadowLayer(4, 0, 1, Color.argb(0x99, 0, 0, 0));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    // ---- события от SponsorManager ------------------------------------------------------

    static void onVideoChanged(String id) {
        if (column == null) return;
        MAIN.post(() -> {
            column.setVisibility(id != null ? View.VISIBLE : View.GONE);
            if (id != null) {
                column.bringToFront();
            }
            if (state == State.ARMED) toast("Отметка отменена: ролик сменился");
            setState(State.IDLE);
        });
    }

    static void onSkipped(Segment s) {
        if (scissors == null || state == State.ARMED) return;
        setState(State.JUST_SKIPPED);
        MAIN.postDelayed(() -> { if (state == State.JUST_SKIPPED) setState(State.IDLE); }, UNDO_MS);
    }

    // ---- стейт-машина ✂ -----------------------------------------------------------------

    private static void onScissors() {
        switch (state) {
            case IDLE: {
                long t1 = SponsorManager.position();
                if (t1 < 0) { toast("Плеер не найден — эта версия TikTok пока не поддерживается"); return; }
                markStart = t1;
                setState(State.ARMED);
                toast("Начало рекламы: " + clock(t1) + ". Нажми ещё раз, когда она кончится");
                break;
            }
            case ARMED: {
                long t2 = SponsorManager.position();
                long t1 = markStart;
                if (t2 - t1 < 1000) { cancel("Слишком коротко — отметка отменена"); return; }
                setState(State.IDLE);
                askCategory(t1, t2);
                break;
            }
            case JUST_SKIPPED: {
                Segment s = SponsorManager.lastSkipped;
                SponsorManager.undoSkip();
                setState(State.IDLE);
                if (s != null) askWasItAd(s);
                break;
            }
        }
    }

    private static void cancel(String why) {
        if (state != State.ARMED) return;
        setState(State.IDLE);
        toast(why);
    }

    private static void setState(State s) {
        state = s;
        if (scissors == null) return;
        if (pulse != null) { pulse.cancel(); pulse = null; scissors.setAlpha(1f); }
        switch (s) {
            case IDLE:
                scissors.setText("✂");
                scissors.setBackground(oval(DIM));
                break;
            case ARMED:
                scissors.setText("■");
                scissors.setBackground(oval(RED));
                pulse = ObjectAnimator.ofFloat(scissors, View.ALPHA, 1f, 0.45f);
                pulse.setDuration(600);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.start();
                break;
            case JUST_SKIPPED:
                scissors.setText("↩");
                scissors.setBackground(oval(DIM));
                break;
        }
    }

    // ---- диалоги ------------------------------------------------------------------------

    private static final String[] CATEGORY_TITLES = {
            "Скам: кейсы, казино, ставки", "Спонсорская вставка", "Самопиар, телеграм-канал", "Интро / заставка"};
    private static final String[] CATEGORY_IDS = {"scam", "sponsor", "selfpromo", "intro"};

    private static void askCategory(long t1, long t2) {
        Activity a = activity.get();
        String id = SponsorManager.currentVideo();
        if (a == null || id == null) return;
        SponsorManager.pause();
        new AlertDialog.Builder(a)
                .setTitle("Реклама " + clock(t1) + " – " + clock(t2) + ". Что это?")
                .setItems(CATEGORY_TITLES, (d, which) -> {
                    long dur = SponsorManager.duration();
                    MimoApi.submit(id, t1, t2, dur > 0 ? dur : t2, CATEGORY_IDS[which],
                            (ok, msg) -> {
                                if (ok) Stats.marked();
                                toast(ok ? "✂ " + msg : "Не отправилось: " + msg);
                            });
                })
                .setNegativeButton("Отмена", null)
                .setOnDismissListener(d -> SponsorManager.resume())
                .show();
    }

    private static void showStats() {
        Activity a = activity.get();
        if (a == null) return;
        new AlertDialog.Builder(a).setTitle("Мимо").setMessage(Stats.text()).setPositiveButton("Ок", null).show();
    }

    private static void askWasItAd(Segment s) {
        Activity a = activity.get();
        if (a == null) return;
        new AlertDialog.Builder(a)
                .setTitle("Вернул на " + clock(s.startMs) + ". Это была не реклама?")
                .setPositiveButton("Не реклама", (d, w) -> {
                    MimoApi.vote(s.uuid, 0);
                    toast("Спасибо, учтём");
                })
                .setNegativeButton("Реклама, просто досмотрю", null)
                .show();
    }

    // ---- Качалка ------------------------------------------------------------------------

    /** tg://resolve?domain=kachalka_videobot&start=tt_<aweme_id> — бот сам соберёт ссылку и отдаст видео. */
    private static void openKachalka() {
        Activity a = activity.get();
        String id = SponsorManager.currentVideo();
        if (a == null || id == null) return;
        String start = "tt_" + id;      // start-параметр Telegram: до 64 символов [A-Za-z0-9_-]
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + BOT + "&start=" + start)));
        } catch (ActivityNotFoundException e) {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + BOT + "?start=" + start)));
        }
    }

    // ---- мелочи -------------------------------------------------------------------------

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static void toast(String text) {
        MAIN.post(() -> {
            try {
                Activity a = activity.get();
                if (a != null) {
                    android.widget.Toast.makeText(a, text, android.widget.Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) { }
        });
    }
}
