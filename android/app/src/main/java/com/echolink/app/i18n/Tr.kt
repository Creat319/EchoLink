package com.echolink.app.i18n

import com.echolink.app.onboarding.AppLanguage
import com.echolink.app.EchoLinkStateHolder

/**
 * 界面文字翻译。
 *
 * 为什么用"中文原文当 key"而不是给每条文案起个 `water.min` 这样的键：
 * 界面上有上百条指标行，其中绝大多数是 `MetricRow("水位最低值", ...)` 这种直接写字面量的。
 * 用中文原文当 key 就可以在 **MetricRow / SectionCard 组件内部统一翻译**，
 * 不用把 100 多处调用点全改一遍，漏改的风险也小得多。
 *
 * 漏翻译的兜底是**回落到中文原文**（宁可显示中文，也不要显示一个 key 或空白）。
 * 为此有一个单元测试会扫描源码里所有中文字面量，断言它们都在本表里——
 * 这条测试是为了防止"选了英文还冒出中文"这类问题再犯。
 */
object Tr {

    private fun current(): AppLanguage = EchoLinkStateHolder.liveSettings.language

    /** 供单元测试断言"界面上的每个中文字面量都有译文"。 */
    fun known(zh: String): Boolean = table.containsKey(zh)

    /** 翻译一条界面文案；没有译文的条目回落到中文原文。 */
    fun t(zh: String): String {
        val language = current()
        if (language == AppLanguage.ZH_CN) {
            return zh
        }

        val entry = table[zh] ?: return zh
        return when (language) {
            AppLanguage.ZH_TW -> entry.first
            AppLanguage.JA -> entry.second
            AppLanguage.EN -> entry.third
            AppLanguage.ZH_CN -> zh
        }
    }

    // ---- 带数字的单位：这类串在运行时拼出来，没法用整串当 key ----

    fun frames(value: Number): String = when (current()) {
        AppLanguage.ZH_CN -> "$value 帧"
        AppLanguage.ZH_TW -> "$value 幀"
        AppLanguage.JA -> "$value フレーム"
        AppLanguage.EN -> "$value frames"
    }

    fun times(value: Number): String = when (current()) {
        AppLanguage.ZH_CN -> "$value 次"
        AppLanguage.ZH_TW -> "$value 次"
        AppLanguage.JA -> "$value 回"
        AppLanguage.EN -> "$value times"
    }

    fun packets(value: Number): String = when (current()) {
        AppLanguage.ZH_CN -> "$value 包"
        AppLanguage.ZH_TW -> "$value 包"
        AppLanguage.JA -> "$value パケット"
        AppLanguage.EN -> "$value packets"
    }

    /** "1.2 包/秒" 这类速率。 */
    fun packetsPerSecond(value: String): String = when (current()) {
        AppLanguage.ZH_CN -> "$value 包/秒"
        AppLanguage.ZH_TW -> "$value 包/秒"
        AppLanguage.JA -> "$value パケット/秒"
        AppLanguage.EN -> "$value pkt/s"
    }

    fun reopens(value: Int): String = when (current()) {
        AppLanguage.ZH_CN -> "重开次数 $value"
        AppLanguage.ZH_TW -> "重開次數 $value"
        AppLanguage.JA -> "再オープン回数 $value"
        AppLanguage.EN -> "Reopened $value times"
    }

    fun manualTarget(value: Int): String = when (current()) {
        AppLanguage.ZH_CN -> "手动目标：$value ms"
        AppLanguage.ZH_TW -> "手動目標：$value ms"
        AppLanguage.JA -> "手動ターゲット：$value ms"
        AppLanguage.EN -> "Manual target: $value ms"
    }

    fun listeningPort(port: Int): String = when (current()) {
        AppLanguage.ZH_CN -> "监听端口 $port。USB 网络共享时，PC 端通常能自动发现网关地址，无需手填。"
        AppLanguage.ZH_TW -> "監聽埠 $port。USB 網路共用時，PC 端通常能自動探索閘道位址，無需手動填寫。"
        AppLanguage.JA -> "待受ポート $port。USB テザリングでは PC 側が通常ゲートウェイを自動検出するため、入力不要です。"
        AppLanguage.EN -> "Listening on port $port. With USB tethering the PC usually finds the gateway automatically, no manual input needed."
    }

    fun newVersionTitle(version: String): String = when (current()) {
        AppLanguage.ZH_CN -> "发现新版本 $version"
        AppLanguage.ZH_TW -> "發現新版本 $version"
        AppLanguage.JA -> "新しいバージョン $version があります"
        AppLanguage.EN -> "Version $version available"
    }

    fun currentVersionLine(version: String): String = when (current()) {
        AppLanguage.ZH_CN -> "当前版本 v$version。"
        AppLanguage.ZH_TW -> "目前版本 v$version。"
        AppLanguage.JA -> "現在のバージョンは v$version です。"
        AppLanguage.EN -> "You are on v$version."
    }

    fun downloadVersion(version: String): String = when (current()) {
        AppLanguage.ZH_CN -> "下载 $version"
        AppLanguage.ZH_TW -> "下載 $version"
        AppLanguage.JA -> "$version をダウンロード"
        AppLanguage.EN -> "Download $version"
    }

    fun buffered(frames: Int, ms: String): String = when (current()) {
        AppLanguage.ZH_CN -> "$frames 帧 / $ms ms"
        AppLanguage.ZH_TW -> "$frames 幀 / $ms ms"
        AppLanguage.JA -> "$frames フレーム / $ms ms"
        AppLanguage.EN -> "$frames frames / $ms ms"
    }

    fun waterSummary(water: String, target: String): String = when (current()) {
        AppLanguage.ZH_CN -> "$water ms 水位 · 目标 $target ms"
        AppLanguage.ZH_TW -> "$water ms 水位 · 目標 $target ms"
        AppLanguage.JA -> "$water ms 水位 · 目標 $target ms"
        AppLanguage.EN -> "$water ms buffered · target $target ms"
    }

    fun currentVersionShort(version: String): String = when (current()) {
        AppLanguage.ZH_CN -> "当前 v$version"
        AppLanguage.ZH_TW -> "目前 v$version"
        AppLanguage.JA -> "現在 v$version"
        AppLanguage.EN -> "Current v$version"
    }

    // ---- 通知栏（应用外可见，必须跟着语言走）----

    fun notifDetail(water: String, target: String, underruns: Long): String = when (current()) {
        AppLanguage.ZH_CN -> "水位 $water ms / 目标 $target ms · 欠载 $underruns"
        AppLanguage.ZH_TW -> "水位 $water ms / 目標 $target ms · 欠載 $underruns"
        AppLanguage.JA -> "水位 $water ms / 目標 $target ms · アンダーラン $underruns"
        AppLanguage.EN -> "Buffer $water ms / target $target ms · underruns $underruns"
    }

    fun notifWaiting(port: Int): String = when (current()) {
        AppLanguage.ZH_CN -> "等待 PC 数据（端口 $port）"
        AppLanguage.ZH_TW -> "等待 PC 資料（埠 $port）"
        AppLanguage.JA -> "PC のデータ待ち（ポート $port）"
        AppLanguage.EN -> "Waiting for PC data (port $port)"
    }

    fun notifReopening(): String = when (current()) {
        AppLanguage.ZH_CN -> "正在重开音频流…"
        AppLanguage.ZH_TW -> "正在重開音訊串流…"
        AppLanguage.JA -> "オーディオストリームを再オープン中…"
        AppLanguage.EN -> "Reopening audio stream…"
    }

    fun notifFault(reason: String?): String = when (current()) {
        AppLanguage.ZH_CN -> reason ?: "音频流故障"
        AppLanguage.ZH_TW -> reason ?: "音訊串流故障"
        AppLanguage.JA -> reason ?: "オーディオストリーム障害"
        AppLanguage.EN -> reason ?: "Audio stream fault"
    }

    fun notifChannelPlayback(): String = when (current()) {
        AppLanguage.ZH_CN -> "音频播放"
        AppLanguage.ZH_TW -> "音訊播放"
        AppLanguage.JA -> "オーディオ再生"
        AppLanguage.EN -> "Audio playback"
    }

    fun notifChannelDescription(): String = when (current()) {
        AppLanguage.ZH_CN -> "EchoLink 接收端播放状态"
        AppLanguage.ZH_TW -> "EchoLink 接收端播放狀態"
        AppLanguage.JA -> "EchoLink 受信側の再生状態"
        AppLanguage.EN -> "EchoLink receiver playback status"
    }

    fun actionStop(): String = when (current()) {
        AppLanguage.ZH_CN -> "停止"
        AppLanguage.ZH_TW -> "停止"
        AppLanguage.JA -> "停止"
        AppLanguage.EN -> "Stop"
    }

    fun actionReopen(): String = when (current()) {
        AppLanguage.ZH_CN -> "用户手动要求重开"
        AppLanguage.ZH_TW -> "使用者手動要求重開"
        AppLanguage.JA -> "ユーザーによる手動再オープン"
        AppLanguage.EN -> "Manual reopen requested by user"
    }

    fun logNote(): String = when (current()) {
        AppLanguage.ZH_CN -> "诊断日志（仅中文）—— 技术排查用，不需要读懂也能用"
        AppLanguage.ZH_TW -> "診斷日誌（僅中文）—— 技術排查用"
        AppLanguage.JA -> "診断ログ（中国語のみ）— 技術調査用"
        AppLanguage.EN -> "Diagnostic log (Chinese only) — for troubleshooting"
    }

    // ---- 网络停顿（WiFi 热点不稳时的直接证据）----

    fun stallLabel(): String = when (current()) {
        AppLanguage.ZH_CN -> "网络成串停顿"
        AppLanguage.ZH_TW -> "網路成串停頓"
        AppLanguage.JA -> "ネットワークの連続停止"
        AppLanguage.EN -> "Burst stalls"
    }

    fun stallValue(events: Long, maxMs: Double): String = when (current()) {
        AppLanguage.ZH_CN -> "$events 次 / 最长 ${"%.0f".format(maxMs)} ms"
        AppLanguage.ZH_TW -> "$events 次 / 最長 ${"%.0f".format(maxMs)} ms"
        AppLanguage.JA -> "$events 回 / 最大 ${"%.0f".format(maxMs)} ms"
        AppLanguage.EN -> "$events times / max ${"%.0f".format(maxMs)} ms"
    }

    fun catchUpLabel(): String = when (current()) {
        AppLanguage.ZH_CN -> "停顿后追赶"
        AppLanguage.ZH_TW -> "停頓後追趕"
        AppLanguage.JA -> "停止後の追い上げ"
        AppLanguage.EN -> "Catch-up bursts"
    }

    fun catchUpValue(events: Long): String = when (current()) {
        AppLanguage.ZH_CN -> "$events 次（与停顿成对出现即为链路问题）"
        AppLanguage.ZH_TW -> "$events 次（與停頓成對出現即為鏈路問題）"
        AppLanguage.JA -> "$events 回（停止と対で出ればリンク問題）"
        AppLanguage.EN -> "$events times (paired with stalls = link problem)"
    }

    /** 成串停顿出现时给的处置建议。 */
    fun stallAdvice(): String = when (current()) {
        AppLanguage.ZH_CN ->
            "检测到网络成串停顿，声音会断续。若在用 WiFi 热点：优先改用 USB 网络共享；" +
                "若必须用热点，把热点频段从 5 GHz 改成 2.4 GHz（部分网卡的 5 GHz 热点会周期性停顿）。"
        AppLanguage.ZH_TW ->
            "偵測到網路成串停頓，聲音會斷續。若在用 WiFi 熱點：優先改用 USB 網路共用；" +
                "若必須用熱點，把熱點頻段從 5 GHz 改成 2.4 GHz（部分網卡的 5 GHz 熱點會週期性停頓）。"
        AppLanguage.JA ->
            "ネットワークの連続停止を検出しました。音が途切れます。WiFi テザリングを使っている場合は、" +
            "まず USB テザリングに切り替えてください。どうしても WiFi なら 5 GHz → 2.4 GHz に変更してください" +
            "（一部の無線カードでは 5 GHz のホットスポットが周期的に停止します）。"
        AppLanguage.EN ->
            "Burst stalls detected — audio will stutter. On a WiFi hotspot, prefer USB tethering; " +
                "if you must use WiFi, switch the hotspot from 5 GHz to 2.4 GHz " +
                "(some adapters' 5 GHz hotspots stall periodically)."
    }

    private val table: Map<String, Triple<String, String, String>> = mapOf(
        // ---- 卡片标题 ----
        "参数核对" to Triple("參數核對", "パラメータ確認", "Parameters"),
        "稳定性" to Triple("穩定性", "安定性", "Stability"),
        "网络（UDP）" to Triple("網路（UDP）", "ネットワーク（UDP）", "Network (UDP)"),
        "正在使用" to Triple("正在使用", "使用中", "In use"),
        "支持但未使用" to Triple("支援但未使用", "対応・未使用", "Supported, unused"),
        "设备不支持" to Triple("裝置不支援", "非対応", "Unsupported"),
        "不可用" to Triple("不可用", "利用不可", "N/A"),
        "等待数据" to Triple("等待資料", "データ待ち", "Waiting for data"),
        "流式中" to Triple("串流中", "ストリーミング中", "Streaming"),
        "已停止" to Triple("已停止", "停止中", "Stopped"),
        "重开中" to Triple("重開中", "再起動中", "Reopening"),
        "故障" to Triple("故障", "障害", "Fault"),
        "发送端" to Triple("發送端", "送信側", "Sender"),
        "测试音" to Triple("測試音", "テスト音", "Test tone"),
        "听到了" to Triple("聽到了", "聞こえた", "Heard it"),
        "没听到" to Triple("沒聽到", "聞こえない", "Didn't hear"),
        "稍后" to Triple("稍後", "後で", "Later"),
        "立即下载" to Triple("立即下載", "今すぐダウンロード", "Download now"),
        "不再提示此版本" to Triple("不再提示此版本", "このバージョンを通知しない", "Skip this version"),
        "检查更新" to Triple("檢查更新", "更新を確認", "Check for updates"),
        "尚未检查" to Triple("尚未檢查", "未確認", "Not checked yet"),
        "仅展示" to Triple("僅展示", "表示のみ", "Display only"),
        "正常" to Triple("正常", "正常", "Normal"),
        "已压低 20 dB" to Triple("已壓低 20 dB", "20 dB 低減中", "Ducked 20 dB"),
        "独占失败" to Triple("獨佔失敗", "排他失敗", "Exclusive failed"),
        "共享模式" to Triple("共享模式", "共有モード", "Shared mode"),
        "剔除开流停顿" to Triple("剔除開流停頓", "ストリーム開始時を除外", "Startup gap excluded"),
        "绕开混音层的关键" to Triple("繞開混音層的關鍵", "ミキサ層を迂回する鍵", "Bypasses the mixer"),
        "应 ≤ burst" to Triple("應 ≤ burst", "burst 以下が理想", "Should be ≤ burst"),
        "不计欠载" to Triple("不計欠載", "アンダーラン不算", "Not counted"),
        "闭环控制量" to Triple("閉環控制量", "閉ループ制御量", "Control variable"),
        "与上一行同批样本" to Triple("與上一行同批樣本", "上の行と同じサンプル", "Same samples as above"),
        "原生累计，不会重置" to Triple("原生累計，不會重置", "ネイティブ累計・リセットなし", "Native cumulative, never reset"),
        "环里没数据，多半是网络成串丢包" to Triple("環裡沒資料，多半是網路成串丟包", "リング枯渇（バースト損失が多い）", "Ring starved, likely burst loss"),
        "回调迟到，调度问题" to Triple("回呼遲到，排程問題", "コールバック遅延（スケジューリング）", "Late callback (scheduling)"),
        "本设备未提供 getXRunCount" to Triple("本裝置未提供 getXRunCount", "この端末は getXRunCount 非対応", "getXRunCount unavailable"),

        // ---- 指标行标签 ----
        "水位最低值（1 s 窗口）" to Triple("水位最低值（1 秒視窗）", "水位最小値（1 秒窓）", "Min buffer (1 s window)"),
        "水位平均值（1 s 窗口）" to Triple("水位平均值（1 秒視窗）", "水位平均値（1 秒窓）", "Avg buffer (1 s window)"),
        "水位历史最低（回调入口）" to Triple("水位歷史最低（回呼入口）", "水位の過去最低（コールバック入口）", "Lowest ever (callback entry)"),
        "当前水位" to Triple("目前水位", "現在の水位", "Current buffer"),
        "目标（低谷）" to Triple("目標（低谷）", "目標（谷）", "Target (trough)"),
        "重采样比率" to Triple("重取樣比率", "リサンプル比", "Resample ratio"),
        "链路抖动 P50" to Triple("鏈路抖動 P50", "リンク揺らぎ P50", "Link jitter P50"),
        "链路抖动 P99" to Triple("鏈路抖動 P99", "リンク揺らぎ P99", "Link jitter P99"),
        "采样率" to Triple("取樣率", "サンプルレート", "Sample rate"),
        "声道" to Triple("聲道", "チャンネル", "Channels"),
        "回调帧数上限" to Triple("回呼幀數上限", "コールバック最大フレーム", "Max callback frames"),
        "回调最大间隔" to Triple("回呼最大間隔", "コールバック最大間隔", "Max callback gap"),
        "回调间隔峰值" to Triple("回呼間隔峰值", "コールバック間隔ピーク", "Callback gap peak"),
        "输出延迟" to Triple("輸出延遲", "出力レイテンシ", "Output latency"),
        "Oboe 缓冲" to Triple("Oboe 緩衝", "Oboe バッファ", "Oboe buffer"),
        "独占模式（拿不到会自动回退共享）" to Triple("獨佔模式（拿不到會自動回退共享）", "排他モード（不可なら共有へ）", "Exclusive (falls back to shared)"),
        "开流尝试" to Triple("開流嘗試", "ストリーム開始試行", "Open attempts"),
        "开流代数" to Triple("開流代數", "ストリーム世代", "Stream generation"),
        "回调次数" to Triple("回呼次數", "コールバック回数", "Callbacks"),
        "已播放" to Triple("已播放", "再生済み", "Played"),
        "收包" to Triple("收包", "受信パケット", "Packets received"),
        "收包速率" to Triple("收包速率", "受信レート", "Packet rate"),
        "丢包" to Triple("丟包", "損失", "Lost"),
        "乱序" to Triple("亂序", "順序逆転", "Out of order"),
        "畸形包" to Triple("畸形包", "不正パケット", "Malformed"),
        "最后一包距今" to Triple("最後一包距今", "最終パケットからの経過", "Since last packet"),
        "发送端重启" to Triple("發送端重啟", "送信側の再起動", "Sender restarts"),
        "时间线跳变" to Triple("時間線跳變", "タイムライン跳び", "Timeline jumps"),
        "欠载次数" to Triple("欠載次數", "アンダーラン回数", "Underruns"),
        "欠载补静音" to Triple("欠載補靜音", "アンダーラン無音補填", "Silence on underrun"),
        "├ 读端饥饿" to Triple("├ 讀端飢餓", "├ 読み手の枯渇", "├ Read starved"),
        "└ 硬件 xrun" to Triple("└ 硬體 xrun", "└ ハードウェア xrun", "└ Hardware xrun"),
        "启动垫水位" to Triple("啟動墊水位", "起動時プリフィル", "Startup prefill"),
        "缺包补静音" to Triple("缺包補靜音", "欠落の無音補填", "Silence on loss"),
        "积压丢帧" to Triple("積壓丟幀", "滞留ドロップ", "Backlog drops"),
        "环缓冲溢出" to Triple("環緩衝溢出", "リング溢れ", "Ring overflow"),
        "时间线重建" to Triple("時間線重建", "タイムライン再構築", "Timeline rebuild"),
        "重开流" to Triple("重開流", "再オープン", "Reopen"),
        "最近重开原因" to Triple("最近重開原因", "直近の再オープン理由", "Last reopen reason"),
        "UDP 端口" to Triple("UDP 埠", "UDP ポート", "UDP port"),
        "保持屏幕常亮" to Triple("保持螢幕常亮", "画面を常時点灯", "Keep screen on"),
        "自动目标水位" to Triple("自動目標水位", "目標水位を自動調整", "Auto target buffer"),
        "上行已发送" to Triple("上行已送出", "上り送信済み", "Uplink sent"),
        "上行电平" to Triple("上行電平", "上りレベル", "Uplink level"),
        "重开次数" to Triple("重開次數", "再オープン回数", "Reopen count"),

        // ---- 长句 ----
        "端口与独占模式的改动在下次“开始接收”时生效。" to Triple(
            "埠與獨佔模式的改動在下次「開始接收」時生效。",
            "ポートと排他モードの変更は次回「受信開始」時に反映されます。",
            "Port and exclusive-mode changes take effect the next time you start receiving.",
        ),
        "需要重启接收才生效；PC 端要选虚拟麦克风（如 CABLE Input）" to Triple(
            "需要重啟接收才生效；PC 端要選虛擬麥克風（如 CABLE Input）",
            "受信の再起動で反映。PC 側で仮想マイク（CABLE Input など）を選択してください。",
            "Takes effect after restarting the receiver; select a virtual mic (e.g. CABLE Input) on the PC.",
        ),
        "刚才那声 1 kHz 提示音，你听到了吗？" to Triple(
            "剛才那聲 1 kHz 提示音，你聽到了嗎？",
            "先ほどの 1 kHz テスト音は聞こえましたか？",
            "Did you hear that 1 kHz test tone?",
        ),
        "这是发现“被静默重路由到蓝牙/其他输出”的唯一务实手段——API 层检测不到，只能靠耳朵。" to Triple(
            "這是發現「被靜默重路由到藍牙/其他輸出」的唯一務實手段——API 層檢測不到，只能靠耳朵。",
            "Bluetooth などへ無言で再ルーティングされる問題を検出する唯一の実用的手段です（API では検知できません）。",
            "The only practical way to catch silent re-routing to Bluetooth or another output — the APIs cannot see it.",
        ),
        "更新包会用浏览器下载，下载完点开安装即可覆盖，设置和数据都不会丢。" to Triple(
            "更新包會用瀏覽器下載，下載完點開安裝即可覆蓋，設定和資料都不會遺失。",
            "更新はブラウザでダウンロードされ、開いてインストールすれば上書きされます。設定とデータは保持されます。",
            "The update downloads in your browser; open it to install over the current version. Settings and data are kept.",
        ),
        "未检测到可用 IPv4 地址" to Triple("未偵測到可用 IPv4 位址", "有効な IPv4 アドレスが見つかりません", "No usable IPv4 address found"),
        "尚未收到数据" to Triple("尚未收到資料", "まだデータを受信していません", "No data received yet"),
        "独占 48k → 独占原生 → 共享" to Triple("獨佔 48k → 獨佔原生 → 共享", "排他 48k → 排他ネイティブ → 共有", "Exclusive 48k → exclusive native → shared"),
        "限 ±0.1%" to Triple("限 ±0.1%", "±0.1% 制限", "Limited to ±0.1%"),
        "读端到底见过多干" to Triple("讀端到底見過多乾", "読み手が見た最小値", "Driest the reader has seen"),
        "我们的环没料" to Triple("我們的環沒料", "リングにデータ無し", "Our ring had no data"),
        "回调没赶上" to Triple("回呼沒趕上", "コールバックが間に合わず", "Callback missed its deadline"),
        "上行麦克风未运行：" to Triple("上行麥克風未執行：", "マイク送信が動作していません：", "Uplink mic not running: "),
    )
}
