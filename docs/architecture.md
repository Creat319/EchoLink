# EchoLink 设计说明

本文解释“为什么这么写”。线格式不在这里，见 [protocol.md](protocol.md)；
实测数据见 [verification.md](verification.md)。

---

## 1. 总览与线程模型

```
PC                                                        Phone
────────────────────────────────────────────────────      ────────────────────────────────────────
[保活渲染线程] 写静音/测试音到端点 ─┐                       [UDP 收包线程] URGENT_AUDIO
                                    ├→ 音频引擎(10ms 周期)   DatagramChannel → DirectByteBuffer
[环回抓取线程] Pro Audio MMCSS      │   → 环回投递              → 解析 16B 头 → engine.push()
  事件驱动 GetBuffer (441/480 帧)   ┘                            → 缺包补静音（保持时间线）
  → 转立体声 → 48k 重采样 → 240 帧分包                    [Oboe 数据回调] 无分配/无锁/无 IO
  → UdpAudioTransport.Send (200/s)                               → 从环形缓冲三次插值读出
                                                                  → 比率 1±0.1% 补偿漂移
[回报接收线程] 解析 40B 状态包                                    → 输出 I16 立体声
[链路线程] 候选地址探测 → 锁定目标
[监控线程] 1Hz：抓取/保活存活、包率、回报超时
                                                          [控制协程 20Hz] 读 stats → LatencyController
                                                                 → setTargetWater / setRatio
                                                          [看门狗 4Hz] 错误回调/停摆/参数核对/测试音
```

**热路径约束**：PC 抓取线程与 Oboe 回调里都不排队、不分配、不加锁。PC 端把转换/重采样/分包/发送
全部挤在抓取线程内完成（一次 960 B 的 `sendto` 是微秒级），避免多一级队列就多一级抖动。
Android 端只有 UDP 线程写环、回调线程读环，中间没有队列。

---

## 2. PC 端

### 2.1 为什么自己写 WASAPI COM interop

方案要求实测 `IAudioClient3::InitializeSharedAudioStream`（路径 A）与
“保活流请求最小周期”（路径 B）。NAudio 2.2.1 只封装到 `IAudioClient`，拿不到 `IAudioClient3`
与 `AudioClientProperties`，所以这部分必须自己做。

取舍：

- **自研**：`Wasapi/ComInterop.cs` 把 `IAudioClient` / `IAudioClient3` / `IAudioCaptureClient` /
  `IAudioRenderClient` 全部写成**扁平 vtable**（接口 IID 给对，方法按 `audioclient.h` 顺序平铺），
  不依赖 C# 接口继承的 COM 编排规则，最容易逐条核对。所有方法 `[PreserveSig]`，因为必须读到
  `0x88890021` 这种具体 HRESULT。
- **NAudio 用在哪**：设备枚举与友好名（`MMDeviceEnumerator`）、诊断 WAV 写出（`WaveFileWriter`）、
  混音格式信息展示。重采样没有用 `WdlResamplingSampleProvider`，因为它要求 pull 模型，
  得再加一条线程和一层缓冲，等于凭空多 1 个 burst 的延迟。
- 明确没做的：`AUDCLNT_STREAMFLAGS_RAW`、按进程抓取（Win10 build 20348+）、自研虚拟声卡。

### 2.2 事件驱动环回

`AUDCLNT_STREAMFLAGS_LOOPBACK | EVENTCALLBACK`，Win10 1703 起原生支持（本机 19045 实测可用）。
抓取线程先 `AvSetMmThreadCharacteristics("Pro Audio")` + `timeBeginPeriod(1)`，再进事件循环：

```
WaitForSingleObject(period 事件, 100ms)
  while GetNextPacketSize > 0:
      GetBuffer → (静默标志? 清零 : 拷贝) → ReleaseBuffer → sink.OnLoopbackData(span)
```

`AUDCLNT_BUFFERFLAGS_SILENT` 与空指针都按真静音处理——宁可送静音也不送脏数据。

**为什么 `hnsBufferDuration` 先试 0**：让引擎给默认缓冲；实测拿到了 22 ms 缓冲，但每个回调仍只投递
一个引擎周期（441/480 帧），说明这 22 ms 是引擎内部的持有区，**没有**变成额外投递延迟。
如果平台拒绝 0，退到“2 个引擎周期”。

### 2.3 保活流 + 路径 B 的顺序依赖

环回在没有程序出声时不产数据，所以需要一条渲染流持续写静音。这条流同时是路径 B 的载体：
Windows 音频引擎对同一端点按“客户端请求的最小周期”传输，所以只要有一个客户端显式请求小周期，
引擎周期就会被拉低。

**顺序很关键**：引擎先起保活流（`KeepAliveRenderer.Start()`），再起环回流（`LoopbackCapture.Start()`）。
反过来环回流会先按默认周期建流，之后再改引擎周期也带不动它。

保活流还承担测试音与点击声的生成——因为它是**同一条**写向该端点的流，声音必然经过设备混音与
环回路径，能真正验证“PC 出声 → 环回 → 网络 → 手机”，而不是在代码里假装播放。

### 2.4 静音本机喇叭

没有用系统静音（不同设备行为不一致：有的设备静音后环回照常有声，有的变全静音）。
推荐做法是默认输出设为虚拟声卡（VB-Cable / AudioRelay 之类），抓该声卡的环回；用户听不到本机
喇叭是预期行为。本机恰好装了 VB-Cable 与 AudioRelay 虚拟声卡，两条路径都实测过。

### 2.5 格式统一与重采样

设备混音格式按 `WAVEFORMATEX` / `WAVEFORMATEXTENSIBLE` 解析，支持 float32 / PCM16 / PCM24 /
PCM32、1–8 声道。下混规则：FL/FR 直达，FC 与环绕按 −3 dB（0.7071）折叠，LFE 丢弃（避免低频过载）。

重采样器 `StreamingResampler`：

- 24 抽头 Kaiser(beta=7) 窗 sinc，256 相位查表 + **相位间线性插值**；
- 每个相位做归一化，保证直流增益为 1（否则音量会跳）；
- 降采样时截止频率按 `min(1, ratio)` 收缩，避免 96 k→48 k 混叠；
- 纯 push/pull 原地处理，实测 44.1 k→48 k 输出帧数与理论值一致（5.17 s 输入产出 248135 帧）。

### 2.6 打包与时间戳语义

每凑满 240 帧发一个 976 B 包：`seq` 自增、`sampleCount` 每包 +240、`timestamp` 用
**名义时间**（`起始微秒 + sampleCount×1000/48`）而不是抓取瞬间的墙钟。

这是刻意的：接收端算的是差分 `d_i = Δ到达 − Δ时间戳`，使用名义时间轴等价于
“到达间隔 − 5 ms”，正好等于接收端要吸收的抖动（含 PC 侧成批投递），恒定偏移被差分消掉。

### 2.7 UDP 与状态回报

一个 socket 双向用：绑定 47811，发往手机 47810；手机把 40 B 回报发回“收到音频的源地址:源端口”
就回到这里，不需要端口协商。`SIO_UDP_CONNRESET` 关掉，避免手机不在线时 ICMP 端口不可达让
`Receive` 抛异常。回报只是显示用途，**音频链路完全不依赖它**。

### 2.8 寻址评分

`TargetDiscovery` 是纯函数（可单测）：对每个 UP 的 IPv4 网卡打分。

- USB 网络共享关键字（rndis/ndis/ncm/usb ethernet/gadget/tether/android）**+60**
- 有默认网关 **+25**（安卓做网关时，手机就是该网段网关）
- 以太网类 **+10**，无线 **−10**（备用通道，降权）
- 虚拟/无关适配器（virtual/vmware/hyper-v/vethernet/bluetooth/wsl/tun/vpn…）直接剔除

分数最高的先探测；探测方式是“向该地址发真实音频包（静音），最多等 1.2 s 看有没有回报”，
有回报就锁定。实机结果：RNDIS 适配器 95 分 → 网关 `192.168.96.150`，普通网卡 35 分排后面。

### 2.9 故障恢复

1 Hz 监控线程负责：抓取线程意外退出（端点失效 `0x88890004`）→ 重建抓取（最多 5 次）；
保活流死亡 → 重建；回报超 5 s → 状态降级但仍继续发。

---

## 3. Android 端

### 3.1 Oboe 开流

```cpp
Direction::Output / PerformanceMode::LowLatency / Format I16 / ChannelCount 2 / Usage::Game
DataCallback + ErrorCallback
```

**开流按三级链尝试，每一级的失败码都进 logcat（也回传给 UI）**：

| 顺序 | 组合 | 为什么 |
| --- | --- | --- |
| 1 | 独占 @48000 | 理想路径 |
| 2 | 独占 @设备原生率（`kUnspecified`） | 不少设备的 MMAP 只接受自己的原生命中率，采样率对不上时 `openStream` 直接失败 |
| 3 | 共享 @48000 | 兜底（官方对照约 +6 ms） |

第 2 级之所以有意义：引擎的读侧本来就用 `baseStep = 48000 / 实际采样率` 折算，拿到 44.1 k 的流也不会变调。

**Oboe 内部缓冲取 2 个 burst**（欠载时按 burst 递增到 4）。AAudio 保证数据回调的尺寸 **≤ burst**，
所以 2 个 burst 就是官方建议的低延迟起点；早期版本压到 4 个 burst（≈21 ms）是基于
"回调可能一次取走整个缓冲"的错误假设，白白给端到端加了 10 ms。
共享模式下 `setBufferCapacityInFrames(1024)` 先把容量要到，开流后再
`setBufferSizeInFrames(2×burst)`；欠载由控制线程调 `setBufferSizeInBursts(n)` 递增。
独占（MMAP）下缓冲由驱动固定，`setBufferSizeInFrames` 返回 `ErrorUnimplemented`（-898）属正常，
只记录不报错；容量提示也**只对共享模式**设置——硬要容量会让独占 `openStream` 直接失败。
实际回调尺寸通过 `maxCallbackFrames` 暴露到 UI，可随时证伪"≤ burst"这个前提。

**性能模式必须保持 LowLatency** —— 真正的大坑是没开低延迟模式（约 205 ms），不是独占失败。

开流后立刻核对实际值（采样率/声道/共享模式/burst/格式），不一致就报给 UI「参数核对」卡。
若实际采样率不是 48 k，`baseStep = 48000/actualRate` 参与读侧步进，音高不会错。

### 3.2 环形缓冲协议

`StereoRing`：左右声道各一条 `int16[16384]`（341 ms），SPSC。

- 写端（UDP 线程）用**绝对帧号**寻址：帧 n 落槽 `n & 0x3FFF`，写指针 `_writeFrame` 用 release 发布；
- 读端（Oboe 回调）只 load acquire 读指针，自己维护 `double _readPos`；
- 读端每次回调把 `floor(_readPos)` 发布到 `_readFrame`，供写端做溢出判断。

**为什么用绝对帧号**：缺包时只要“写 N 帧静音”就能保持时间线连续，不需要任何重排、等待或重传。

写端（`pushFrames`）处理四种情况：

| 情况 | 处理 |
| --- | --- |
| `ref == 期望` | 正常写入 |
| `ref > 期望`，缺口 ≤200 ms | 补等长静音，计入 `silenceFilled` |
| `ref > 期望`，缺口 >200 ms | 不补静音（否则水位被顶爆），重建时间线 |
| `ref < 期望` | 整包丢弃或只写未重叠的尾部 |
| 环写满 | 丢本包 + 请求读端跳到“写头 − 目标水位” |

“重建时间线 / 跳水位”通过 `_requestedReadPos`（原子，-1 表示无请求）交给读端采纳，
读端采纳时做 2.5 ms 淡入。**永远不在回调里清空缓冲**，否则坐标会乱。

### 3.3 读侧：插值与欠载语义

- **启动先垫水位**：开流/重建后回调只输出静音、**不消费**，直到环里攒够 target 帧才开始读。
  没有这一步，水位会收敛到“刚好够一次回调”的临界点（PHJ110 实测恒为 10.2 ms，而目标 22.2 ms），
  目标水位就只是个数字——这是真机 2475 次欠载的直接原因。垫水位期间的静音单独计数，**不算欠载**，
  不然会把控制器吓到一直抬高目标；
- 每个输出帧按 `baseStep × ratio` 推进 `_readPos`；
- 用 `idx-1 .. idx+2` 四个点做 Catmull-Rom 三次插值（线性插值的高频镜像明显得多）；
- **欠载时**：本回调剩下的帧输出静音，**但不推进 `_readPos`**（样本事后不丢，水位随后由比率拉回）——
  这是方案里“插静音且事后不丢样本”的落地方式；
- **积压 >2× 目标**才丢帧：把 `_readPos` 跳到 `写头 − 目标`，并对**新起点**做 2.5 ms 淡入，避免咔哒声。
  只做淡入不做淡出：被跳过的段落整体丢弃，其尾部不会被听到；真正的交叉淡出需要额外 lookahead，
  在回调里做不划算。

### 3.4 四项加固的落点

| 加固 | 落点 | 说明 |
| --- | --- | --- |
| 错误回调自动重开 | `onErrorAfterClose` 置原子标志 → 控制协程 `reopen()` | 不在回调里重开（Oboe 要求换线程） |
| 回调停摆检测 | 看门狗比对 `callbackCount` 是否推进 | 连续 400 ms 不推进即重开 |
| 开流后核对参数 | `verifyStreamParameters()` → UI | 采样率/声道/共享模式/burst/缓冲时长/独占失败码 |
| 首次连接测试音 | native `playBeep`（回调内混音，独立于网络） | UI 弹确认卡：“听到了吗？” |
| 路由变化兜底 | `AudioRouteMonitor`：`AudioDeviceCallback` + `ACTION_HEADSET_PLUG` | 某些版本 AAudio 收不到断开消息 |
| 路由事件去重 | 输出设备集合签名 + 2 s 限流 | 实测一次变化连发 12 条，每条都会触发重开（150 ms 空洞） |

测试音刻意在 **native 回调里混音**而不是塞进环形缓冲：这样它与网络、水位、AEC 全部无关，
只验证“声音到没到用户的耳朵”，正是检测静默重路由需要的性质。

### 3.5 前台服务与线程

`mediaPlayback` 前台服务 + `PARTIAL_WAKE_LOCK`（`setReferenceCounted(false)`），
通知栏每秒刷新一次水位/目标/欠载，常驻“停止”动作。UDP 线程用
`THREAD_PRIORITY_URGENT_AUDIO`。控制闭环与看门狗放在 `limitedParallelism(1)` 的单线程
dispatcher 上，天然顺序执行，不需要锁。

---

## 4. 漂移补偿闭环

### 4.1 为什么用差分抖动

两端时钟不同源，绝对时刻没有可比性；但

```
d_i = (到达_i − 到达_{i−1}) − (时间戳_i − 时间戳_{i−1})
```

只做差分，恒定时钟偏置被消掉，剩下的是一包间隔（5 ms）内的相对漂移 + 网络/调度抖动，
正好是水位需要吸收的量。`|d_i|` 进 0.25 ms 分桶直方图（256 桶，覆盖 64 ms）。

### 4.2 目标水位

```
控制量：最近 1 秒内水位的最小值（原生按每次回调统计，控制线程取 20 个周期的 min）
目标：  target = clamp(burst + 5 ms, 6 ms, 60 ms)     // PHJ110：5.33 + 5 ≈ 10.3 ms
欠载：  立即 target += 1 burst，且 1 秒内只处理一次
涨：≤2 ms/周期    收：≤0.5 ms/周期
抖动 P99：只展示，不参与控制
```

**为什么控制最小值而不是平均值**：PC 端按引擎周期成批投递，水位是锯齿。
控制均值会系统性地高估半个批次（约 5 ms）；更要紧的是 20 Hz 控制周期正好是 10 ms 批的整数倍，
采样相位被锁死在锯齿的固定位置，两端时钟缓慢漂移时还会出现慢拍干扰。
控制窗口最小值没有这两个问题，且下限只需覆盖"一次回调可能取走的量 + 余量"。

**为什么目标里不再出现抖动 P99**：`P99 + 5 ms` 已经隐含了 PC 侧批处理延迟，
而 PC 侧延迟本来就会体现在到达间隔里——用它定目标等于把同一份延迟算了两遍
（实测名义时间戳下 `|dᵢ|` P99 在 HDMI 上是 5.25 ms，换成真实发送时刻后只剩 0.25 ms）。

**水位与 Oboe 缓冲解耦**：环水位吸收网络与批处理抖动，Oboe 缓冲吸收回调调度抖动，
两者是不同来源，不存在"环必须 ≥ 缓冲 × 1.15"的物理关系（那个系数会让两级互相抬高）。

收缩慢是刻意的：缩太快会在抖动还没兑现时又欠载，形成"缩—欠载—涨"的振荡。

### 4.3 比率环路

```
error = 水位 − 目标
ratioTarget = clamp(1 + error × 5e-5, 1±0.001)     // 欠载当拍强制 1.0，不许继续排空
ratio += (ratioTarget − ratio) × 0.01              // 一阶低通，τ ≈ 5 s @20 Hz
ratio = clamp(ratio, 前值 ± 1e-5)                  // 斜率限幅
```

两级限幅的作用：±0.1% 保证音高偏移最大约 1.7 音分（不可闻）；1e-5/周期的斜率限幅保证
即使在阶跃扰动下比率也是“缓慢爬”，不会出现可闻的音高滑动或延迟跳变。

### 4.4 升级路径

当前实现是“平滑水位 + 只用重采样补偿”的 scrcpy 式做法。若实测不收敛（表现为水位长期单向漂移或
持续小欠载），按方案升级为「累计读写样本数 + 滤波后时间戳 + DLL」。判据与观测方法见
[testing.md](testing.md) §3。Adriaensen 的论文指出直接对水位取平均会带来长期不收敛误差，
这正是触发条件。

---

## 5. 关键取舍一览

| 取舍 | 选择 | 理由 |
| --- | --- | --- |
| PC 端重采样线程模型 | 自研 push 模型 | 少一条线程、少一层缓冲，少 1 个 burst 延迟 |
| 包头时间戳 | 名义时间轴 | 让接收端的差分抖动直接可用 |
| 静音本机喇叭 | 建议虚拟声卡，不用系统静音 | 设备行为不一致 |
| 手机端插值 | Catmull-Rom | 质量/成本折中，明显优于线性 |
| 欠载处理 | 补静音但不推进读指针 | “事后不丢样本”，水位自愈 |
| 丢帧 | 仅 >2× 目标，且淡入 | 避免频繁丢帧造成可闻劣化；淡出需 lookahead，不值得 |
| 回报通道 | 单向为主，回报仅诊断 | 不引入 ACK/重传依赖 |
| 测试音 | native 回调混音 | 与网络/水位解耦，只验证听不听得到 |
