package com.echolink.app

import com.echolink.app.net.UsbTetherDetector
import com.echolink.app.onboarding.AppLanguage
import com.echolink.app.onboarding.stringsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导页的两块纯逻辑：USB 网卡名判定、语言表完整性。
 *
 * 这两处错了都不会崩，只会静默给出错误结果——比如把 wlan0 当成 USB 连上了，
 * 用户就会卡在"下一步"永远点不了；或者某种语言漏了文案，界面上冒出空白。
 */
class OnboardingLogicTest {

    // ---- USB 网卡判定 ----

    @Test
    fun rndisInterfaceIsUsb() {
        // Android 的 USB 网卡通常叫 rndis0 / usb0
        assertTrue(UsbTetherDetector.isUsbInterfaceName("rndis0"))
        assertTrue(UsbTetherDetector.isUsbInterfaceName("usb0"))
        assertTrue(UsbTetherDetector.isUsbInterfaceName("usb1"))
    }

    @Test
    fun usbInterfaceMatchIsCaseInsensitive() {
        assertTrue(UsbTetherDetector.isUsbInterfaceName("RNDIS0"))
        assertTrue(UsbTetherDetector.isUsbInterfaceName("Usb0"))
    }

    @Test
    fun wifiAndCellularAreNotUsb() {
        assertFalse(UsbTetherDetector.isUsbInterfaceName("wlan0"))
        assertFalse(UsbTetherDetector.isUsbInterfaceName("rmnet_data0"))
        assertFalse(UsbTetherDetector.isUsbInterfaceName("ccmni0"))
        assertFalse(UsbTetherDetector.isUsbInterfaceName("eth0"))
        assertFalse(UsbTetherDetector.isUsbInterfaceName("lo"))
    }

    @Test
    fun nullOrBlankIsNotUsb() {
        assertFalse(UsbTetherDetector.isUsbInterfaceName(null))
        assertFalse(UsbTetherDetector.isUsbInterfaceName(""))
        assertFalse(UsbTetherDetector.isUsbInterfaceName("   "))
    }

    // ---- 兜底判据：名字认不出来、但看着就是给电脑用的那条网 ----
    // 换成 NetworkInterface 枚举之后加的这一层，因为有些 OEM 的 tether 网卡不叫 rndis/usb

    @Test
    fun unknownNameWithPrivateIpv4IsTether() {
        // 华为/小米等可能有奇怪命名，靠私有地址兜住
        assertTrue(UsbTetherDetector.isLikelyTetherInterface("eth0", false, true, "192.168.42.129"))
        assertTrue(UsbTetherDetector.isLikelyTetherInterface("rev_rmnet8", false, true, "192.168.96.1"))
        assertTrue(UsbTetherDetector.isLikelyTetherInterface("ncm0", false, true, "10.10.0.1"))
    }

    @Test
    fun knownUpstreamInterfacesAreNotTether() {
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("wlan0", false, true, "192.168.1.5"))
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("rmnet_data0", false, true, "10.1.2.3"))
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("ccmni0", false, true, "10.1.2.3"))
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("ap0", false, true, "192.168.43.1"))
    }

    @Test
    fun publicIpv4IsNotTether() {
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("eth0", false, true, "8.8.8.8"))
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("eth0", false, true, "172.32.0.1"))
    }

    @Test
    fun loopbackDownOrMissingAddressIsNotTether() {
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("lo", true, true, "127.0.0.1"))
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("eth0", false, false, "192.168.42.1"))
        assertFalse(UsbTetherDetector.isLikelyTetherInterface("eth0", false, true, null))
        assertFalse(UsbTetherDetector.isLikelyTetherInterface(null, false, true, "192.168.42.1"))
    }

    // ---- 语言 ----

    @Test
    fun allFourLanguagesExist() {
        assertEquals(
            listOf("zh-CN", "zh-TW", "ja", "en"),
            AppLanguage.entries.map { it.tag },
        )
    }

    @Test
    fun tagRoundTrips() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }

    @Test
    fun unknownTagFallsBackToSimplifiedChinese() {
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromTag(""))
    }

    @Test
    fun tagMatchIgnoresCase() {
        assertEquals(AppLanguage.ZH_TW, AppLanguage.fromTag("ZH-tw"))
        assertEquals(AppLanguage.EN, AppLanguage.fromTag("EN"))
    }

    /** 每种语言都必须给出非空的关键文案，否则界面上会出现空按钮。 */
    @Test
    fun everyLanguageHasNonBlankStrings() {
        AppLanguage.entries.forEach { language ->
            val s = stringsFor(language)
            assertEquals(language, s.language)

            val required = listOf(
                s.next, s.back, s.skip, s.start, s.finish,
                s.welcomeTitle, s.welcomeBody, s.languageTitle,
                s.intro1Title, s.intro1Body,
                s.intro2Title, s.intro2Body,
                s.connectTitle, s.connectBody, s.connectCableHint,
                s.connectWaiting, s.connectDetected, s.connectNextBlocked, s.connectSkipHint,
                s.appTitle, s.btnStartReceive, s.btnStop,
                s.sectionWater, s.sectionPlayback, s.sectionNetwork, s.sectionStability,
                s.sectionAddress, s.sectionSettings, s.sectionVersion, s.sectionMic, s.sectionLog,
                s.settingsReplayGuide,
            )

            required.forEach { text ->
                assertTrue("${language.tag} 有空文案", text.isNotBlank())
            }

            assertTrue("${language.tag} 介绍要点为空", s.intro1Bullets.isNotEmpty())
            assertTrue("${language.tag} 介绍要点为空", s.intro2Bullets.isNotEmpty())
            assertTrue("${language.tag} 连接步骤为空", s.connectSteps.isNotEmpty())
        }
    }

    /** 四种语言的文案不该互相照抄（漏翻译时最典型的症状）。 */
    @Test
    fun translationsDifferBetweenLanguages() {
        val welcomes = AppLanguage.entries.map { stringsFor(it).welcomeTitle }
        assertEquals("四种语言的欢迎语有重复", welcomes.size, welcomes.distinct().size)

        val skips = AppLanguage.entries.map { stringsFor(it).skip }
        assertEquals("四种语言的跳过按钮有重复", skips.size, skips.distinct().size)
    }

    @Test
    fun chineseAndJapaneseAreNotNull() {
        assertNotNull(stringsFor(AppLanguage.ZH_TW).connectCableHint)
        assertNotNull(stringsFor(AppLanguage.JA).connectCableHint)
        assertNotNull(stringsFor(AppLanguage.EN).connectCableHint)
    }
}
