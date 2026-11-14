#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>

#include <oboe/Oboe.h>
#include <oboe/OboeExtensions.h>

#include "ring_buffer.h"

namespace echolink {

// nativeGetStats 返回的 DoubleArray 下标。Kotlin 侧 NativeAudioEngine.Stats 必须与此一一对应。
enum StatsIndex : int {
    kWaterFrames = 0,       // 当前水位（帧）
    kTargetWaterFrames = 1, // 当前目标水位（帧）
    kFramesPlayed = 2,      // 已交给音频设备的帧数
    kUnderrunEvents = 3,
    kUnderrunFrames = 4,
    kDropEvents = 5,        // 积压丢帧次数
    kDropFrames = 6,
    kSilenceFilled = 7,     // 缺包补静音帧数
    kResyncEvents = 8,      // 断流重建次数
    kSampleRate = 9,        // 实际输出采样率
    kChannels = 10,
    kFramesPerBurst = 11,
    kSharingMode = 12,      // 0=Exclusive 1=Shared
    kLatencyMs = 13,        // Oboe calculateLatencyMillis
    kStreamGeneration = 14, // 每次开流 +1，供 Kotlin 检测重开
    kErrorFlag = 15,        // 1 = onErrorAfterClose 已报告，需要重开
    kLastErrorCode = 16,
    kCallbackCount = 17,
    kMaxCallbackGapMs = 18,
    kRatio = 19,            // 当前重采样比率
    kWriteHead = 20,        // 已写入的绝对帧数
    kRingCapacity = 21,
    kOverflowDrops = 22,    // 环形缓冲写满丢弃的帧数
    kStarted = 23,          // 1 = 流在跑
    kBaseStep = 24,         // 每个输出帧消耗的流帧数（含采样率修正）
    kOpenResult = 25,       // 最后一次 openStream() 的 Result 码
    kBufferSizeFrames = 26, // 实际 buffer size（帧）
    kBufferCapacityFrames = 27,
    kRequestedExclusive = 28,  // 1 = 本次请求了独占
    kBufferSetResult = 29,     // setBufferSizeInFrames 的返回码
    kOpenAttempts = 30,        // 尝试了几次才开流成功
    kExclusiveDeniedCode = 31, // 独占尝试失败时的 Result 码（0 = 没失败过）
    kPrefillFrames = 32,       // 启动垫水位期间输出的静音帧数（不算欠载）
    kWaterMinFrames = 33,      // 自上次读取以来水位的最小值（闭环控制量）
    kMaxCallbackFrames = 34,   // 回调收到的最大帧数（用于验证 numFrames <= burst）
    kPrefillActive = 35,       // 1 = 正在垫水位
    kSchedGapMaxMs = 36,       // 区间内"调度型"回调间隔峰值（已剔除开流停顿，读后重置）
    kMMapSupported = 37,       // 1 = 设备支持 MMAP（isMMapSupported）
    kMMapEnabled = 38,         // 1 = 本应用允许走 MMAP（isMMapEnabled）
    kMMapUsed = 39,            // 1 = 实际打开的流走了 MMAP（isMMapUsed）
    kXRunCount = 40,           // AAudio 自己统计的硬件侧 xrun（getXRunCount，-1 = 不可用）
    kReadStarveFrames = 41,    // 读端取不到数据而补静音的帧数（与 xrun 分开计数）
    kWaterAtCallbackMin = 42,  // 回调入口水位的最小值（ms 口径由 Kotlin 换算），用于对账
    kStatsCount = 43,          // 固定 43：Kotlin 侧 DoubleArray(43)，两侧必须一致
};

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    // 控制线程调用
    int32_t start(int32_t sampleRate, bool exclusive);
    int32_t stop();
    int32_t reopen();  // 错误回调/看门狗触发：1) 设备路由变化 2) 回调停摆

    // UDP 线程调用：写入一个包（按绝对帧号对齐，缺包补静音）
    void pushFrames(int64_t refFrameIndex, const int16_t* pcm, int32_t frames);

    void resetRing();

    void setTargetWaterFrames(int32_t frames) { _targetWaterFrames.store(frames, std::memory_order_relaxed); }
    int32_t targetWaterFrames() const { return _targetWaterFrames.load(std::memory_order_relaxed); }

    void setResampleRatio(double ratio);
    double resampleRatio() const { return _ratio.load(std::memory_order_relaxed); }

    /// 请求重新垫水位（欠载过深时由控制线程调用，比靠比率慢慢抽干快得多）
    void requestPrefill() { _prefillPending.store(true, std::memory_order_relaxed); }

    /// 调整 Oboe 内部缓冲为 N 个 burst（LatencyTuner 的简化版，只在欠载时往上加）
    int32_t setBufferSizeInBursts(int32_t bursts);

    void playBeep();

    void fillStats(double* out, int32_t length);

private:
    // Oboe 回调（音频线程）
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;
    void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result error) override;

    int32_t openStreamLocked(bool exclusive);
    void closeStreamLocked();
    int32_t readFrames(int16_t* out, int32_t frames);
    void mixBeep(int16_t* out, int32_t frames);

    StereoRing _ring;

    std::mutex _controlMutex;
    std::shared_ptr<oboe::AudioStream> _stream;
    int32_t _requestedSampleRate = 48000;
    bool _exclusiveRequested = true;

    // 生产者状态（只有 UDP 线程碰，控制线程复位时持 _producerMutex）
    std::mutex _producerMutex;
    uint64_t _expectedRef = 0;
    bool _timelineStarted = false;

    // 消费端状态（只有音频回调线程碰）
    double _readPos = 1.0;
    int32_t _fadeInRemaining = 0;
    float _beepPhase = 0.0f;

    // 启动垫水位：开流/重建后先静音等待，直到环里攒够 target 帧再开始消费。
    // 没有这一步，水位会停在“刚好够一次回调”的临界点（PHJ110 实测 10 ms），目标水位形同虚设。
    // 用 atomic：断流重建时生产者线程也会把它置回 true。
    std::atomic<bool> _prefillPending{true};

    // 生产者 -> 消费者 的重定位请求（-1 = 无请求）
    std::atomic<int64_t> _requestedReadPos{-1};
    std::atomic<double> _baseStep{1.0};

    // 丢帧迟滞状态（只有回调线程读写）
    bool _dropActive = false;

    // 补静音后需要淡入的帧数（只有写端读写，持 _producerMutex）
    int32_t _pendingFadeIn = 0;

    // 统计（多线程读写，全用 atomic）
    std::atomic<int32_t> _targetWaterFrames{1440};  // 30 ms @48k
    std::atomic<double> _ratio{1.0};
    std::atomic<uint64_t> _framesPlayed{0};
    std::atomic<uint64_t> _underrunEvents{0};
    std::atomic<uint64_t> _underrunFrames{0};
    std::atomic<uint64_t> _dropEvents{0};
    std::atomic<uint64_t> _dropFrames{0};
    std::atomic<uint64_t> _silenceFilled{0};
    std::atomic<uint64_t> _resyncEvents{0};
    std::atomic<uint64_t> _overflowDrops{0};
    std::atomic<uint64_t> _callbackCount{0};
    std::atomic<uint64_t> _readStarveFrames{0};      // 读端饥饿补静音的帧数
    std::atomic<int64_t> _waterAtCallbackMin{INT64_MAX}; // 回调入口水位最小值（帧）
    std::atomic<uint64_t> _streamGeneration{0};
    std::atomic<int32_t> _errorFlag{0};
    std::atomic<int32_t> _lastErrorCode{0};
    std::atomic<int64_t> _lastCallbackNanos{0};
    std::atomic<int64_t> _maxCallbackGapNanos{0};
    std::atomic<int32_t> _beepFramesRemaining{0};
    std::atomic<int32_t> _started{0};

    // 实际流参数（开流后一次性写入，读侧只读）
    std::atomic<int32_t> _actualSampleRate{48000};
    std::atomic<int32_t> _actualChannels{2};
    std::atomic<int32_t> _framesPerBurst{0};
    std::atomic<int32_t> _sharingMode{1};
    std::atomic<int32_t> _latencyUsec{0};

    // 开流诊断：独占为什么没拿到、Oboe 内部缓冲到底给了多大
    std::atomic<int32_t> _openResult{0};
    std::atomic<int32_t> _bufferSizeFrames{0};
    std::atomic<int32_t> _bufferCapacityFrames{0};
    std::atomic<int32_t> _requestedExclusive{0};
    std::atomic<int32_t> _bufferSetResult{0};
    std::atomic<int32_t> _openAttempts{0};
    std::atomic<int32_t> _exclusiveDeniedCode{0};
    std::atomic<uint64_t> _prefillFrames{0};
    std::atomic<int64_t> _waterMinSinceRead{INT64_MAX};
    std::atomic<int32_t> _maxCallbackFrames{0};
    std::atomic<int64_t> _schedGapMaxNs{0};   // 读后重置；剔除开流停顿与 >100 ms 的流级空档
    int64_t _firstCallbackNanos = 0;          // 仅回调线程读写（用于跳过开流阶段）

    // MMAP 能力探测：这三项决定"低延迟还有没有别的路可走"，见 docs/design-review.md §5.1
    std::atomic<int32_t> _mmapSupported{0};
    std::atomic<int32_t> _mmapEnabled{0};
    std::atomic<int32_t> _mmapUsed{0};
};

}  // namespace echolink
