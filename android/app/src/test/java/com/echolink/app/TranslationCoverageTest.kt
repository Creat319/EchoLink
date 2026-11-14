package com.echolink.app

import com.echolink.app.i18n.Tr
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防复发：界面源码里出现的每个中文字面量，都必须在 [Tr] 的翻译表里。
 *
 * 这条测试是为了根治"选了英文还冒出中文"——靠人眼检查上百个指标行是查不干净的，
 * 但只要新增一行中文忘了加译文，这里就会红。
 *
 * 扫描范围只包括**界面层**（ui 包 + 通知）。日志是技术排查用的，
 * 刻意保持中文并在界面上标注，不在此列。
 */
class TranslationCoverageTest {

    private val uiSources = listOf(
        "src/main/java/com/echolink/app/ui/EchoLinkScreen.kt",
        "src/main/java/com/echolink/app/ui/Components.kt",
        "src/main/java/com/echolink/app/onboarding/OnboardingScreen.kt",
        "src/main/java/com/echolink/app/AudioReceiverService.kt",
    )

    /** 匹配中文字面量；跳过注释行。 */
    private val literal = Regex("""["']([^"']*[\u4e00-\u9fff][^"']*)["']""")

    private fun projectRoot(): File {
        // 单元测试的工作目录是模块根（android/app）
        val here = File(".").absoluteFile
        return if (File(here, "src/main").exists()) here else here.parentFile
    }

    @Test
    fun everyChineseLiteralInUiHasTranslation() {
        val missing = mutableListOf<String>()
        var scanned = 0

        for (relative in uiSources) {
            val file = File(projectRoot(), relative)
            if (!file.exists()) {
                continue
            }

            scanned++
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                // 注释里的中文不是界面文案
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                    return@forEachIndexed
                }

                literal.findAll(rawLine).forEach { match ->
                    val text = match.groupValues[1]
                    // 带插值的串由 Tr 的格式化函数负责，不按整串查表
                    if (text.contains("$")) {
                        return@forEach
                    }

                    if (!Tr.known(text)) {
                        missing += "$relative:${index + 1}  \"$text\""
                    }
                }
            }
        }

        assertTrue("没有扫描到界面源码，路径可能不对", scanned > 0)
        assertTrue(
            "以下中文字面量缺少译文（选了英文会显示中文）：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun translationTableHasAllThreeForeignLanguages() {
        // 抽查若干高频文案，确保三种外语都不是空串
        val samples = listOf(
            "欠载次数", "收包", "丢包", "乱序", "畸形包", "输出延迟", "Oboe 缓冲",
            "水位最低值（1 s 窗口）", "启动垫水位", "最近重开原因", "独占失败",
        )

        samples.forEach { zh ->
            assertTrue("缺少条目：$zh", Tr.known(zh))
        }
    }
}
