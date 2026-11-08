package com.echolink.app

import com.echolink.app.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较的语义边界。
 *
 * 界面上的"有新版本"提示完全依赖这个比较，错一个方向就会出现
 * "天天提示有新版本"或"有更新却不提示"——两者都比没有更新检查更糟。
 */
class UpdateCheckerTest {

    @Test
    fun equalVersionsAreNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.3.0", "0.3.0"))
    }

    @Test
    fun patchIncrementIsNewer() {
        assertTrue(UpdateChecker.isNewer("0.3.1", "0.3.0"))
    }

    @Test
    fun numericCompareNotLexicographic() {
        // 字符串比较会把 "0.3.10" 判成小于 "0.3.9"
        assertTrue(UpdateChecker.isNewer("0.3.10", "0.3.9"))
        assertFalse(UpdateChecker.isNewer("0.3.9", "0.3.10"))
    }

    @Test
    fun serverOlderThanClientIsNotNewer() {
        // 服务端回滚时不能提示"有新版本"
        assertFalse(UpdateChecker.isNewer("0.2.9", "0.3.0"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertEquals(0, UpdateChecker.compareVersions("0.3", "0.3.0"))
        assertTrue(UpdateChecker.isNewer("0.4", "0.3.9"))
    }

    @Test
    fun leadingVIsTolerated() {
        assertTrue(UpdateChecker.isNewer("v0.3.1", "0.3.0"))
        assertEquals(0, UpdateChecker.compareVersions("v0.3.0", "0.3.0"))
    }

    @Test
    fun nonNumericSuffixIsIgnored() {
        assertEquals(0, UpdateChecker.compareVersions("0.3.0-beta", "0.3.0"))
    }

    @Test
    fun emptyOrNullIsTreatedAsZero() {
        assertTrue(UpdateChecker.isNewer("0.0.1", ""))
        assertFalse(UpdateChecker.isNewer("", "0.0.1"))
        assertFalse(UpdateChecker.isNewer(null, "0.3.0"))
    }
}
