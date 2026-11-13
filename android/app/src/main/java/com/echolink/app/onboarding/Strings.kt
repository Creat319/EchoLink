package com.echolink.app.onboarding

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 引导流程的语言。
 *
 * 为什么不用 Android 的 res/values-xx 资源：那套跟随**系统语言**，而这里要的是
 * **应用内自选**（用户在引导第一页就挑，且要能覆盖系统语言）。
 * 自己维护一张表虽然朴素，但行为完全可控、不依赖 appcompat 的 per-app language。
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    ZH_CN("zh-CN", "简体中文"),
    ZH_TW("zh-TW", "繁體中文"),
    JA("ja", "日本語"),
    EN("en", "English"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: ZH_CN
    }
}

/** 引导页与主界面共用的文案表。 */
interface Strings {
    val language: AppLanguage

    // ---- 通用 ----
    val next: String
    val back: String
    val skip: String
    val start: String
    val finish: String

    // ---- 第 1 页：欢迎 + 选语言 ----
    val welcomeTitle: String
    val welcomeBody: String
    val languageTitle: String

    // ---- 第 2 页：这是什么 ----
    val intro1Title: String
    val intro1Body: String
    val intro1Bullets: List<String>

    // ---- 第 3 页：为什么延迟低 ----
    val intro2Title: String
    val intro2Body: String
    val intro2Bullets: List<String>

    // ---- 第 4 页：连 USB ----
    val connectTitle: String
    val connectBody: String
    val connectCableHint: String
    val connectSteps: List<String>
    val connectWaiting: String
    val connectDetected: String
    val connectNextBlocked: String
    val connectSkipHint: String
    val connectInterfacesLabel: String
    val connectManualContinue: String

    // ---- 主界面 ----
    val appTitle: String
    val btnStartReceive: String
    val btnStop: String
    val sectionWater: String
    val sectionPlayback: String
    val sectionNetwork: String
    val sectionStability: String
    val sectionAddress: String
    val sectionSettings: String
    val sectionVersion: String
    val sectionMic: String
    val sectionLog: String
    val settingsReplayGuide: String
}

// ---------------------------------------------------------------------------
// 简体中文
// ---------------------------------------------------------------------------
object ZhCn : Strings {
    override val language = AppLanguage.ZH_CN

    override val next = "下一步"
    override val back = "上一步"
    override val skip = "跳过引导"
    override val start = "开始使用"
    override val finish = "完成"

    override val welcomeTitle = "欢迎使用 EchoLink"
    override val welcomeBody = "把手机变成电脑的无线音响——也可以反过来，让手机当电脑的麦克风。"
    override val languageTitle = "选择语言"

    override val intro1Title = "手机当电脑音响"
    override val intro1Body = "电脑上播放的任何声音，都会实时送到手机，用手机的扬声器或耳机放出来。"
    override val intro1Bullets = listOf(
        "电脑端：抓取系统输出的声音（WASAPI 环回）",
        "手机端：低延迟播放，不卡顿不爆音",
        "中间的通道：一根 USB 数据线",
    )

    override val intro2Title = "为什么延迟低"
    override val intro2Body = "没有编码压缩、不走服务器，声音直接在你的电脑和手机之间传。"
    override val intro2Bullets = listOf(
        "不经压缩，不做编解码，省掉一整段处理时间",
        "不经过任何服务器，局域网内直达",
        "实测端到端约 45 毫秒，看视频基本对得上口型",
    )

    override val connectTitle = "先连一根线"
    override val connectBody = "把手机和电脑用数据线连起来，并打开手机的「USB 网络共享」。"
    override val connectCableHint = "不知道 USB 线是什么？拿你平时给手机充电的那根线，把手机和电脑连上试试——能传数据就能用。"
    override val connectSteps = listOf(
        "用数据线连接手机和电脑",
        "手机上从顶部下拉通知栏，找到「USB」",
        "选择「USB 网络共享」（不同手机可能叫「共享网络」）",
        "看到下面的状态变成绿色就可以了",
    )
    override val connectWaiting = "还没检测到 USB 网络共享"
    override val connectDetected = "已检测到 USB 网络共享，可以继续"
    override val connectNextBlocked = "连接成功后才能点「下一步」"
    override val connectSkipHint = "也可以先跳过，这些步骤之后还能在电脑端界面上看到。"
    override val connectInterfacesLabel = "当前网卡"
    override val connectManualContinue = "检测不到？手动继续"

    override val appTitle = "EchoLink"
    override val btnStartReceive = "开始接收"
    override val btnStop = "停止"
    override val sectionWater = "水位闭环"
    override val sectionPlayback = "播放链路（Oboe / AAudio）"
    override val sectionNetwork = "网络（UDP）"
    override val sectionStability = "稳定性"
    override val sectionAddress = "本机地址（PC 端手动填这个）"
    override val sectionSettings = "设置"
    override val sectionVersion = "版本"
    override val sectionMic = "上行麦克风"
    override val sectionLog = "日志"
    override val settingsReplayGuide = "重新查看引导"
}

// ---------------------------------------------------------------------------
// 繁體中文
// ---------------------------------------------------------------------------
object ZhTw : Strings {
    override val language = AppLanguage.ZH_TW

    override val next = "下一步"
    override val back = "上一步"
    override val skip = "跳過引導"
    override val start = "開始使用"
    override val finish = "完成"

    override val welcomeTitle = "歡迎使用 EchoLink"
    override val welcomeBody = "把手機變成電腦的無線音響——也可以反過來，讓手機當電腦的麥克風。"
    override val languageTitle = "選擇語言"

    override val intro1Title = "手機當電腦音響"
    override val intro1Body = "電腦上播放的任何聲音，都會即時送到手機，用手機的喇叭或耳機放出來。"
    override val intro1Bullets = listOf(
        "電腦端：擷取系統輸出的聲音（WASAPI 環回）",
        "手機端：低延遲播放，不卡頓不爆音",
        "中間的通道：一條 USB 傳輸線",
    )

    override val intro2Title = "為什麼延遲低"
    override val intro2Body = "沒有編碼壓縮、不經過伺服器，聲音直接在你的電腦和手機之間傳。"
    override val intro2Bullets = listOf(
        "不壓縮、不做編解碼，省掉一整段處理時間",
        "不經過任何伺服器，區域網路內直達",
        "實測端對端約 45 毫秒，看影片大致對得上嘴型",
    )

    override val connectTitle = "先接一條線"
    override val connectBody = "把電腦和手機用傳輸線接起來，並開啟手機的「USB 網路共用」。"
    override val connectCableHint = "不知道 USB 線是什麼？拿你平時給手機充電的那條線，把手機和電腦接起來試試——能傳資料就能用。"
    override val connectSteps = listOf(
        "用傳輸線連接手機和電腦",
        "手機上從頂端下拉通知欄，找到「USB」",
        "選擇「USB 網路共用」",
        "看到下面的狀態變成綠色就可以了",
    )
    override val connectWaiting = "還沒偵測到 USB 網路共用"
    override val connectDetected = "已偵測到 USB 網路共用，可以繼續"
    override val connectNextBlocked = "連線成功後才能點「下一步」"
    override val connectSkipHint = "也可以先跳過，這些步驟之後還能在電腦端介面上看到。"
    override val connectInterfacesLabel = "目前網卡"
    override val connectManualContinue = "偵測不到？手動繼續"

    override val appTitle = "EchoLink"
    override val btnStartReceive = "開始接收"
    override val btnStop = "停止"
    override val sectionWater = "水位閉環"
    override val sectionPlayback = "播放鏈路（Oboe / AAudio）"
    override val sectionNetwork = "網路（UDP）"
    override val sectionStability = "穩定性"
    override val sectionAddress = "本機位址（PC 端手動填這個）"
    override val sectionSettings = "設定"
    override val sectionVersion = "版本"
    override val sectionMic = "上行麥克風"
    override val sectionLog = "日誌"
    override val settingsReplayGuide = "重新查看引導"
}

// ---------------------------------------------------------------------------
// 日本語
// ---------------------------------------------------------------------------
object Ja : Strings {
    override val language = AppLanguage.JA

    override val next = "次へ"
    override val back = "戻る"
    override val skip = "ガイドをスキップ"
    override val start = "はじめる"
    override val finish = "完了"

    override val welcomeTitle = "EchoLink へようこそ"
    override val welcomeBody = "スマホを PC のワイヤレススピーカーに。逆に、スマホを PC のマイクとしても使えます。"
    override val languageTitle = "言語を選択"

    override val intro1Title = "スマホを PC のスピーカーに"
    override val intro1Body = "PC で再生している音をそのままスマホへ送り、スマホのスピーカーやイヤホンで鳴らします。"
    override val intro1Bullets = listOf(
        "PC 側：システム出力の音を取得（WASAPI ループバック）",
        "スマホ側：低遅延で再生、途切れや音割れなし",
        "つなぐのは USB ケーブル 1 本だけ",
    )

    override val intro2Title = "なぜ低遅延なのか"
    override val intro2Body = "圧縮もエンコードもせず、サーバーも経由しません。音は PC とスマホの間を直接流れます。"
    override val intro2Bullets = listOf(
        "非圧縮・コーデックなしで、処理時間を丸ごと削減",
        "サーバーを経由せず、LAN 内で直接転送",
        "実測で端末間およそ 45 ミリ秒。動画の口元とほぼ一致します",
    )

    override val connectTitle = "まずケーブルを接続"
    override val connectBody = "スマホと PC を USB ケーブルでつなぎ、スマホの「USB テザリング」をオンにします。"
    override val connectCableHint = "USB ケーブルがどれか分からないときは、普段スマホを充電しているケーブルを PC につないでみてください。データ転送ができれば使えます。"
    override val connectSteps = listOf(
        "USB ケーブルでスマホと PC を接続",
        "スマホの通知シェードを開き「USB」をタップ",
        "「USB テザリング」を選択",
        "下の状態が緑になれば準備完了です",
    )
    override val connectWaiting = "USB テザリングを検出していません"
    override val connectDetected = "USB テザリングを検出しました。続行できます"
    override val connectNextBlocked = "接続が成功すると「次へ」を押せます"
    override val connectSkipHint = "スキップしても、この手順は PC 側の画面でいつでも確認できます。"
    override val connectInterfacesLabel = "現在のインターフェース"
    override val connectManualContinue = "検出されない場合は手動で続行"

    override val appTitle = "EchoLink"
    override val btnStartReceive = "受信開始"
    override val btnStop = "停止"
    override val sectionWater = "バッファ水位の制御"
    override val sectionPlayback = "再生経路（Oboe / AAudio）"
    override val sectionNetwork = "ネットワーク（UDP）"
    override val sectionStability = "安定性"
    override val sectionAddress = "この端末のアドレス（PC 側で入力）"
    override val sectionSettings = "設定"
    override val sectionVersion = "バージョン"
    override val sectionMic = "マイク送信"
    override val sectionLog = "ログ"
    override val settingsReplayGuide = "ガイドをもう一度見る"
}

// ---------------------------------------------------------------------------
// English
// ---------------------------------------------------------------------------
object En : Strings {
    override val language = AppLanguage.EN

    override val next = "Next"
    override val back = "Back"
    override val skip = "Skip guide"
    override val start = "Get started"
    override val finish = "Done"

    override val welcomeTitle = "Welcome to EchoLink"
    override val welcomeBody = "Turn your phone into a wireless speaker for your PC — or the other way round, and use your phone as the PC's microphone."
    override val languageTitle = "Choose your language"

    override val intro1Title = "Your phone as a PC speaker"
    override val intro1Body = "Whatever your PC plays is sent to the phone in real time and comes out of its speaker or headphones."
    override val intro1Bullets = listOf(
        "PC side: captures the system output (WASAPI loopback)",
        "Phone side: low-latency playback, no stutter, no crackle",
        "The link between them: a single USB cable",
    )

    override val intro2Title = "Why the latency is low"
    override val intro2Body = "No compression, no codec, no server. Audio travels straight between your PC and phone."
    override val intro2Bullets = listOf(
        "Uncompressed — no encode/decode step at all",
        "Never leaves your local network",
        "About 45 ms end to end, close enough for lip sync",
    )

    override val connectTitle = "Connect the cable first"
    override val connectBody = "Connect your phone to the PC with a USB cable and turn on USB tethering on the phone."
    override val connectCableHint = "Not sure what a USB cable is? Take the cable you normally use to charge your phone, plug the phone into the PC and see — if it can carry data, it works."
    override val connectSteps = listOf(
        "Connect the phone to the PC with the cable",
        "Pull down the notification shade on the phone and tap \"USB\"",
        "Choose \"USB tethering\"",
        "Wait until the status below turns green",
    )
    override val connectWaiting = "USB tethering not detected yet"
    override val connectDetected = "USB tethering detected — you can continue"
    override val connectNextBlocked = "\"Next\" becomes available once connected"
    override val connectSkipHint = "You can also skip this; the same steps are shown on the PC side."
    override val connectInterfacesLabel = "Interfaces"
    override val connectManualContinue = "Not detected? Continue anyway"

    override val appTitle = "EchoLink"
    override val btnStartReceive = "Start receiving"
    override val btnStop = "Stop"
    override val sectionWater = "Watermark control"
    override val sectionPlayback = "Playback path (Oboe / AAudio)"
    override val sectionNetwork = "Network (UDP)"
    override val sectionStability = "Stability"
    override val sectionAddress = "This device's address (enter it on the PC)"
    override val sectionSettings = "Settings"
    override val sectionVersion = "Version"
    override val sectionMic = "Uplink microphone"
    override val sectionLog = "Log"
    override val settingsReplayGuide = "Show the guide again"
}

fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.ZH_CN -> ZhCn
    AppLanguage.ZH_TW -> ZhTw
    AppLanguage.JA -> Ja
    AppLanguage.EN -> En
}
/**
 * 让任意层级的 Composable 直接取到当前语言文案，不必给每个组件加参数。
 * 默认给简体中文，忘记提供也不会崩。
 */
val LocalStrings = staticCompositionLocalOf<Strings> { ZhCn }