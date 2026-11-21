package com.echolink.app

import android.media.MediaRecorder
import com.echolink.app.audio.MicProcessing
import com.echolink.app.audio.MicSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 麦克风采集源策略。
 *
 * 这组断言锁住的是"有没有回声消除"这条分水岭：
 * 早期版本把 VOICE_COMMUNICATION 排在最后（怕和自研 AEC 叠加），
 * 结果自研 AEC 一直没做，实际效果就是完全没有回声消除。
 */
class MicSourcePolicyTest {

    @Test
    fun voiceModePrefersVoiceCommunication() {
        val candidates = MicSourcePolicy.candidates(voiceMode = true)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, candidates.first())
        assertTrue(candidates.contains(MediaRecorder.AudioSource.MIC))
    }

    @Test
    fun rawModePrefersUnprocessed() {
        val candidates = MicSourcePolicy.candidates(voiceMode = false)
        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, candidates.first())
        assertFalse(candidates.contains(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
    }

    @Test
    fun bothChainsEndWithPlainMicAsLastResort() {
        assertEquals(MediaRecorder.AudioSource.MIC, MicSourcePolicy.candidates(true).last())
        assertEquals(MediaRecorder.AudioSource.MIC, MicSourcePolicy.candidates(false).last())
    }

    @Test
    fun platformAecOnlyOnVoiceCommunication() {
        assertTrue(MicSourcePolicy.supportsPlatformAec(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
        assertFalse(MicSourcePolicy.supportsPlatformAec(MediaRecorder.AudioSource.UNPROCESSED))
        assertFalse(MicSourcePolicy.supportsPlatformAec(MediaRecorder.AudioSource.VOICE_RECOGNITION))
        assertFalse(MicSourcePolicy.supportsPlatformAec(MediaRecorder.AudioSource.MIC))
    }

    @Test
    fun labelsAreStableForUi() {
        assertEquals("VOICE_COMMUNICATION", MicSourcePolicy.label(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
        assertEquals("UNPROCESSED", MicSourcePolicy.label(MediaRecorder.AudioSource.UNPROCESSED))
        assertEquals("MIC", MicSourcePolicy.label(MediaRecorder.AudioSource.MIC))
    }

    @Test
    fun defaultsFavourEchoCancellationButNotAgc() {
        val defaults = MicProcessing()
        assertTrue("默认应走语音链路", defaults.voiceMode)
        assertTrue("默认应开回声消除", defaults.aec)
        assertTrue("默认应开降噪", defaults.ns)
        assertFalse("默认不开 AGC：它会把底噪一起抬起来", defaults.agc)
    }
}
