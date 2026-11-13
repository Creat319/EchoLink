package com.echolink.app

import com.echolink.app.update.UpdateChecker
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 起一个本地极简 HTTP 服务端，验证"发请求 → 解析 JSON → 比较版本"整条路径。
 *
 * 之前只测了版本比较，**没测过真正解析接口返回值的那段代码**，而它是这条链路里
 * 唯一依赖网络的部分，字段名或返回体一变就会静默失败。
 *
 * 不用 com.sun.net.httpserver：它不在 Android 单测的编译类路径上。
 * 这里用裸 ServerSocket 手写响应，零额外依赖。
 */
class UpdateCheckerEndpointTest {

    companion object {
        private lateinit var server: ServerSocket
        private lateinit var acceptThread: Thread
        private var baseUrl = ""

        private val routes = mapOf(
            "/ok" to (200 to """{"code":0,"version":"0.3.10","scope":"user"}"""),
            "/older" to (200 to """{"code":0,"version":"0.2.9","scope":"user"}"""),
            "/same" to (200 to """{"code":0,"version":"0.3.3","scope":"user"}"""),
            "/badcode" to (200 to """{"code":7,"version":"9.9.9","scope":"user"}"""),
            "/noversion" to (200 to """{"code":0,"scope":"user"}"""),
            "/notjson" to (200 to "<html>502 Bad Gateway</html>"),
            "/error" to (500 to """{"code":0,"version":"9.9.9"}"""),
        )

        @BeforeClass
        @JvmStatic
        fun start() {
            server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            baseUrl = "http://127.0.0.1:${server.localPort}"

            acceptThread = Thread({
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }

                    Thread { serve(socket) }.apply { isDaemon = true }.start()
                }
            }, "test-http").apply {
                isDaemon = true
                start()
            }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val requestLine = reader.readLine() ?: return
                    // 读掉请求头直到空行，否则客户端可能等不到响应
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }

                    val path = requestLine.split(" ").getOrNull(1) ?: "/"
                    val (status, body) = routes[path] ?: (404 to "not found")
                    val bytes = body.toByteArray()

                    OutputStreamWriter(s.getOutputStream()).apply {
                        write("HTTP/1.1 $status OK\r\n")
                        write("Content-Type: application/json\r\n")
                        write("Content-Length: ${bytes.size}\r\n")
                        write("Connection: close\r\n")
                        write("\r\n")
                        flush()
                    }
                    s.getOutputStream().write(bytes)
                    s.getOutputStream().flush()
                }
            } catch (_: Throwable) {
                // 单个连接出错不影响其他用例
            }
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            runCatching { server.close() }
        }
    }

    @Test
    fun newerVersionIsParsedAndFlagged() {
        val result = UpdateChecker.check("0.3.3", "$baseUrl/ok")
        assertNotNull(result)
        assertEquals("0.3.10", result!!.latestVersion)
        assertTrue(result.isNewer)
    }

    @Test
    fun olderVersionIsNotFlagged() {
        val result = UpdateChecker.check("0.3.3", "$baseUrl/older")
        assertNotNull(result)
        assertFalse(result!!.isNewer)
    }

    @Test
    fun sameVersionIsNotFlagged() {
        val result = UpdateChecker.check("0.3.3", "$baseUrl/same")
        assertNotNull(result)
        assertFalse(result!!.isNewer)
    }

    @Test
    fun nonZeroCodeIsTreatedAsNoAnswer() {
        assertNull(UpdateChecker.check("0.3.3", "$baseUrl/badcode"))
    }

    @Test
    fun missingVersionIsTreatedAsNoAnswer() {
        assertNull(UpdateChecker.check("0.3.3", "$baseUrl/noversion"))
    }

    @Test
    fun nonJsonBodyIsTreatedAsNoAnswer() {
        // 服务器挂掉返回 HTML 错误页时，解析异常不能冒到 UI
        assertNull(UpdateChecker.check("0.3.3", "$baseUrl/notjson"))
    }

    @Test
    fun httpErrorIsTreatedAsNoAnswer() {
        assertNull(UpdateChecker.check("0.3.3", "$baseUrl/error"))
    }

    @Test
    fun unreachableHostIsTreatedAsNoAnswer() {
        // 离线是常态，不能抛异常
        assertNull(UpdateChecker.check("0.3.3", "http://127.0.0.1:1/none"))
    }

    // ---- 弹窗策略 ----

    @Test
    fun autoCheckPromptsForUnskippedVersion() {
        assertTrue(UpdateChecker.shouldPrompt("0.3.4", null, manual = false))
        assertTrue(UpdateChecker.shouldPrompt("0.3.4", "0.3.3", manual = false))
    }

    @Test
    fun autoCheckStaysQuietForSkippedVersion() {
        assertFalse(UpdateChecker.shouldPrompt("0.3.4", "0.3.4", manual = false))
    }

    @Test
    fun manualCheckAlwaysPrompts() {
        // 用户主动点了检查，即使之前说过"不再提示"也必须给结果
        assertTrue(UpdateChecker.shouldPrompt("0.3.4", "0.3.4", manual = true))
    }
}
