package app.revanced.extension.tiktok.mimo;

import android.content.Context;
import android.content.SharedPreferences;

import app.revanced.extension.shared.Utils;

/** Личная статистика: живёт только на телефоне, никуда не отправляется. Показывается по долгому нажатию на ✂. */
final class Stats {
    private static SharedPreferences prefs() {
        try {
            Context c = Utils.getContext();
            return c == null ? null : c.getSharedPreferences("mimo", Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    static void skipped(long ms) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit().putLong("skipped", p.getLong("skipped", 0) + 1)
                .putLong("saved_ms", p.getLong("saved_ms", 0) + Math.max(0, ms)).apply();
    }

    static void marked() {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putLong("marked", p.getLong("marked", 0) + 1).apply();
    }

    static String text() {
        SharedPreferences p = prefs();
        if (p == null) return "Статистика пока недоступна";
        long sec = p.getLong("saved_ms", 0) / 1000;
        String saved = sec >= 3600 ? (sec / 3600) + " ч " + (sec % 3600 / 60) + " мин"
                : sec >= 60 ? (sec / 60) + " мин " + (sec % 60) + " с" : sec + " с";
        return "Пропущено реклам: " + p.getLong("skipped", 0)
                + "\nСэкономлено времени: " + saved
                + "\nОтмечено вами: " + p.getLong("marked", 0)
                + "\n\nСчётчики хранятся только на этом телефоне.";
    }
}
