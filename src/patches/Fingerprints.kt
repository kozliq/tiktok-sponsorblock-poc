package app.revanced.patches.tiktok.mimo

import app.revanced.patcher.*
import app.revanced.patcher.patch.BytecodePatchContext

// Плеер ByteDance. Класс SDK публичный (com.ss.ttvideoengine.TTVideoEngine, тот же, что в открытом
// VEVodDemo-android от Volcengine): play(), getCurrentPlaybackTime(), getDuration(),
// seekTo(int, SeekCompletionListener). Публичный API SDK обычно не обфусцируют, но это
// надо подтвердить на конкретном APK: tools/find_hooks.py.
internal val BytecodePatchContext.enginePlayMethod by gettingFirstMethodDeclaratively {
    name("play")
    definingClass("Lcom/ss/ttvideoengine/TTVideoEngine;")
    returnType("V")
    parameterTypes()
}

// Перемотка: seekTo(int ms, SeekCompletionListener). Нужна, чтобы отличить перемотку человека
// (полоса прокрутки) от своей: попал руками в рекламу — значит, хочет её посмотреть.
// "L" — любой объектный тип: точное имя слушателя покажет tools/find_hooks.py.
internal val BytecodePatchContext.engineSeekMethod by gettingFirstMethodDeclaratively {
    definingClass("Lcom/ss/ttvideoengine/TTVideoEngine;")
    returnType("V")
    parameterTypes("I", "Lo6f/c0;")
}

// Главный экран: сюда вешаем кнопки «✂» и «⤓» поверх ленты.
internal val BytecodePatchContext.mainActivityOnCreateMethod by gettingFirstMethodDeclaratively {
    name("onCreate")
    definingClass("Lcom/ss/android/ugc/aweme/main/MainActivity;")
    returnType("V")
    parameterTypes("Landroid/os/Bundle;")
}

// Остальные точки уже найдены и поддерживаются ReVanced, берём их как есть:
//   onRenderFirstFrameMethod  (patches/tiktok/shared)     — первый кадр нового ролика в ленте
//   feedApiServiceLIZMethod   (patches/tiktok/feedfilter) — пришла новая порция ленты (FeedItemList)
