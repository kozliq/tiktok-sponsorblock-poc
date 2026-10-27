"""Проверка точек входа «Мимо» на конкретном APK TikTok — до сборки, без телефона.

    python3 -m venv ~/.venvs/mimo && ~/.venvs/mimo/bin/pip install androguard==4.1.3
    ~/.venvs/mimo/bin/python -I tools/find_hooks.py TikTok-36.5.4.apk

Печатает ✔/✘ по каждой точке. Любой ✘ — фингерпринт в Fingerprints.kt надо поправить под эту версию.
"""
import sys

from androguard.misc import AnalyzeAPK

CHECKS = [
    ("плеер: TTVideoEngine.play()V", "Lcom/ss/ttvideoengine/TTVideoEngine;", "play", "()V"),
    ("плеер: getCurrentPlaybackTime()", "Lcom/ss/ttvideoengine/TTVideoEngine;", "getCurrentPlaybackTime", "()"),
    ("плеер: getDuration()", "Lcom/ss/ttvideoengine/TTVideoEngine;", "getDuration", "()"),
    ("плеер: seekTo(I, …)", "Lcom/ss/ttvideoengine/TTVideoEngine;", "seekTo", "(I"),
    ("лента: getCurrentAweme()", "/BaseListFragmentPanel;", "getCurrentAweme", "()Lcom/ss/android/ugc/aweme/feed/model/Aweme;"),
    ("лента: FeedApiService.fetchFeedList", "/FeedApiService;", "fetchFeedList", ""),
    ("модель: Aweme.getAid()", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "getAid", "()Ljava/lang/String;"),
    ("модель: Aweme.getShareUrl()", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "getShareUrl", "()"),
    ("экран: MainActivity.onCreate(Bundle)", "Lcom/ss/android/ugc/aweme/main/MainActivity;", "onCreate", "(Landroid/os/Bundle;)V"),
]
STRINGS = [("первый кадр: строка onRenderFirstFrame", "method_enable_viewpager_preload_duration")]


def main(apk):
    _, dexes, dx = AnalyzeAPK(apk)
    methods = [(m.class_name, m.name, m.descriptor) for m in dx.get_methods()]
    bad = 0
    for title, cls, name, desc in CHECKS:
        hits = [f"{c}->{n}{d}" for c, n, d in methods
                if (c == cls or (cls.startswith("/") and c.endswith(cls))) and n == name and d.startswith(desc)]
        bad += not hits
        print(("✔ " if hits else "✘ ") + title + (f"   {hits[0]}" if hits else ""))
    for title, text in STRINGS:
        found = any(True for _ in dx.find_strings(text))
        bad += not found
        print(("✔ " if found else "✘ ") + title)
    print("\nВсё на месте — можно собирать." if not bad else f"\n{bad} точек не найдено — правим Fingerprints.kt.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
