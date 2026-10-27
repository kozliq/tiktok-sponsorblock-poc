package app.revanced.patches.tiktok.mimo

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.tiktok.feedfilter.feedApiServiceLIZMethod
import app.revanced.patches.tiktok.misc.extension.sharedExtensionPatch
import app.revanced.patches.tiktok.shared.onRenderFirstFrameMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Element

private const val MANAGER = "Lapp/revanced/extension/tiktok/mimo/SponsorManager;"
private const val BUTTONS = "Lapp/revanced/extension/tiktok/mimo/MimoButtons;"

private val fixManifestResourcePatch = resourcePatch(
    description = "Removes dummy split APK meta-data from AndroidManifest.xml",
) {
    apply {
        document("AndroidManifest.xml").use { document ->
            val metaNodes = document.getElementsByTagName("meta-data")
            for (i in 0 until metaNodes.length) {
                val node = metaNodes.item(i) as? Element ?: continue
                if (node.getAttribute("android:name") == "com.android.vending.splits") {
                    node.parentNode?.removeChild(node)
                    break
                }
            }
        }
    }
}

@Suppress("unused")
val mimoPatch = bytecodePatch(
    name = "Mimo",
    description = "SponsorBlock для TikTok: пропускает вшитую в ролики рекламу (кейсы, казино, " +
        "телеграм-каналы) по таймкодам из открытой базы, даёт отметить рекламу в два тапа " +
        "и скачать ролик без водяного знака и без рекламы через бота Качалки.",
) {
    dependsOn(sharedExtensionPatch, fixManifestResourcePatch)

    compatibleWith(
        "com.ss.android.ugc.trill"("36.5.4"),
        "com.zhiliaoapp.musically"("36.5.4"),
    )

    apply {
        // 1. Новый ролик в ленте. BaseListFragmentPanel.getCurrentAweme() — модель текущего ролика,
        //    так же её берёт патч «Playback speed». v0 в начале метода свободен (локальный регистр).
        onRenderFirstFrameMethod.addInstructions(
            0,
            """
                invoke-virtual { p0 }, Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;->getCurrentAweme()Lcom/ss/android/ugc/aweme/feed/model/Aweme;
                move-result-object v0
                invoke-static { v0 }, $MANAGER->onVideoChanged(Ljava/lang/Object;)V
            """,
        )

        // 2. Плеер начал играть: запоминаем экземпляр движка (слабой ссылкой), им и будем перематывать.
        //    /range — на случай, если p0 окажется регистром старше v15.
        enginePlayMethod.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $MANAGER->onEnginePlay(Ljava/lang/Object;)V",
        )

        // 2б. Перемотка: p1 — миллисекунды. Свою перемотку расширение узнаёт по флагу и пропускает.
        engineSeekMethod.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $MANAGER->onEngineSeek(Ljava/lang/Object;I)V",
        )

        // 3. Пришла порция ленты: заранее тянем таймкоды на все её ролики, чтобы к свайпу они уже были.
        //    Точка та же, что у «Feed filter»: перед return-object, с тем же регистром.
        feedApiServiceLIZMethod.apply {
            val ret = instructions.first { it.opcode == Opcode.RETURN_OBJECT }
            val register = (ret as OneRegisterInstruction).registerA
            addInstruction(
                ret.location.index,
                "invoke-static/range { v$register .. v$register }, $MANAGER->onFeed(Ljava/lang/Object;)V",
            )
        }

        // 4. Кнопки поверх ленты — в конце MainActivity.onCreate, перед каждым return-void.
        mainActivityOnCreateMethod.apply {
            instructions.filter { it.opcode == Opcode.RETURN_VOID }
                .map { it.location.index }
                .sortedDescending()
                .forEach { index ->
                    addInstruction(index, "invoke-static/range { p0 .. p0 }, $BUTTONS->attach(Landroid/app/Activity;)V")
                }
        }
    }
}
