#include "audio_engine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>

#define LOG_TAG "EchoLinkEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace echolink {

namespace {
constexpr int32_t kBeepFrames = 19200;            // 400 ms @48k
constexpr uint64_t kMaxGapFrames = 9600;          // 200 ms：以内的缺包补静音，超过就当断流重建
constexpr int32_t kFadeFrames = 120;              // 2.5 ms 丢帧淡入
constexpr double kPi = 3.14159265358979323846;
constexpr float kBeepAmplitude = 0.25f;
constexpr int32_t kBeepFadeFrames = 480;          // 10 ms 淡入淡出
// 注意：不能叫 kChannels —— StatsIndex 里已经有一个同名枚举值，会变成二义引用
constexpr int32_t kOutputChannels = 2;

// Oboe 内部缓冲的起始大小（单位：burst）。AAudio 的回调尺寸 <= burst，
// 所以 2 个 burst 足够；真出现 xrun 时由 Kotlin 侧按 burst 往上加（LatencyTuner 式）。
// 早期版本用 4 个 burst 是为了防“一次回调要整个缓冲”，实测不成立（见 maxCallbackFrames）。
constexpr int32_t kBurstsPerBuffer = 2;
constexpr int32_t kMinBufferCapacityFrames = 1024;
constexpr int32_t kGapFadeFrames = 48;  // 1 ms @48k：缺包静音洞两端的淡出/淡入长度

inline int64_t nowNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

inline int16_t clamp16(float value) {
    if (value > 32767.0f) return 32767;
    if (value < -32768.0f) return -32768;
    return static_cast<int16_t>(value);
}

// Catmull-Rom 三次插值：比线性插值的镜像分量小得多，48k 全带内基本听不出内插失真
inline float cubic(float p0, float p1, float p2, float p3, float x) {
    const float c1 = 0.5f * (p2 - p0);
    const float c2 = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
    const float c3 = 0.5f * (p3 - p0) + 1.5f * (p1 - p2);
    return ((c3 * x + c2) * x + c1) * x + p1;
}
}  // namespace

AudioEngine::AudioEngine() {
    _ring.clear();
    _streamGeneration.store(0);
}

AudioEngine::~AudioEngine() {
    stop();
}

int32_t AudioEngine::start(int32_t sampleRate, bool exclusive) {
    std::lock_guard<std::mutex> lock(_controlMutex);
    if (_stream) {
        return 0;
    }

    _requestedSampleRate = sampleRate > 0 ? sampleRate : 48000;
    _exclusiveRequested = exclusive;

    {
        std::lock_guard<std::mutex> producerLock(_producerMutex);
        _ring.clear();
        _timelineStarted = false;
        _expectedRef = 0;
    }

    _readPos = 1.0;
    _fadeInRemaining = 0;
    _prefillPending.store(true, std::memory_order_relaxed);
    _prefillFrames.store(0, std::memory_order_relaxed);

    return openStreamLocked(exclusive);
}

int32_t AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(_controlMutex);
    closeStreamLocked();
    return 0;
}

int32_t AudioEngine::reopen() {
    std::lock_guard<std::mutex> lock(_controlMutex);
    LOGI("reopen requested (generation=%llu, error=%d)",
         static_cast<unsigned long long>(_streamGeneration.load()),
         _lastErrorCode.load());

    closeStreamLocked();
    _errorFlag.store(0);

    {
        std::lock_guard<std::mutex> producerLock(_producerMutex);
        _ring.clear();
        _timelineStarted = false;
        _expectedRef = 0;
    }

    _readPos = 1.0;
    _fadeInRemaining = 0;
    _beepPhase = 0.0f;
    _prefillPending.store(true, std::memory_order_relaxed);
    _prefillFrames.store(0, std::memory_order_relaxed);

    return openStreamLocked(_exclusiveRequested);
}

void AudioEngine::resetRing() {
    std::lock_guard<std::mutex> producerLock(_producerMutex);
    _ring.clear();
    _timelineStarted = false;
    _expectedRef = 0;
    _silenceFilled.store(0);
}

void AudioEngine::setResampleRatio(double ratio) {
    // 偏差限 ±0.1%（见 docs/architecture.md 参数表），越界直接钳住
    if (ratio < 0.999) ratio = 0.999;
    if (ratio > 1.001) ratio = 1.001;
    _ratio.store(ratio, std::memory_order_relaxed);
}

void AudioEngine::playBeep() {
    _beepPhase = 0.0f;
    _beepFramesRemaining.store(kBeepFrames, std::memory_order_relaxed);
}

int32_t AudioEngine::setBufferSizeInBursts(int32_t bursts) {
    std::lock_guard<std::mutex> lock(_controlMutex);
    if (!_stream) {
        return -1;
    }

    const int32_t burst = _stream->getFramesPerBurst();
    if (burst <= 0 || bursts <= 0) {
        return -1;
    }

    int32_t desired = burst * bursts;
    const int32_t capacity = _stream->getBufferCapacityInFrames();
    if (capacity > 0) {
        desired = std::min(desired, capacity);
    }

    if (desired <= _stream->getBufferSizeInFrames()) {
        return 0;  // 不需要改（独占模式下往往也改不动）
    }

    const oboe::ResultWithValue<int32_t> result = _stream->setBufferSizeInFrames(desired);
    const oboe::Result status = result;
    _bufferSetResult.store(static_cast<int32_t>(status));
    _bufferSizeFrames.store(_stream->getBufferSizeInFrames());

    LOGI("bump buffer to %d bursts -> %d frames (%s)", bursts, _stream->getBufferSizeInFrames(),
         oboe::convertToText(status));
    return status == oboe::Result::OK ? _stream->getBufferSizeInFrames() : -2;
}

int32_t AudioEngine::openStreamLocked(bool exclusive) {
    _requestedExclusive.store(exclusive ? 1 : 0);
    _exclusiveDeniedCode.store(0);
    _openAttempts.store(0);

    // 依次尝试三种组合，每次失败都把 Oboe 的 Result 码打进 logcat：
    //   1) 独占 @48000    —— 理想路径
    //   2) 独占 @设备原生率 —— 不少设备的 MMAP 只接受自己的原生命中率，
    //                        采样率对不上时 openStream 直接失败（InvalidFormat/Unavailable）。
    //                        用后的实际采样率会通过 baseStep 折算，48k 数据不会变调。
    //   3) 共享 @48000    —— 兜底（官方对照约 +6 ms）
    // 显式设置 format/sampleRate 是刻意的：不指定时 AAudio 自己挑，独占更容易因“不匹配”失败。
    struct Attempt {
        oboe::SharingMode sharing;
        int32_t sampleRate;
        const char* label;
    };

    const Attempt attempts[] = {
        {oboe::SharingMode::Exclusive, _requestedSampleRate, "exclusive@48000"},
        {oboe::SharingMode::Exclusive, oboe::kUnspecified, "exclusive@native"},
        {oboe::SharingMode::Shared, _requestedSampleRate, "shared@48000"},
    };

    oboe::Result result = oboe::Result::ErrorInternal;
    const char* usedLabel = "none";
    int32_t attemptsUsed = 0;

    for (const Attempt& attempt : attempts) {
        if (!exclusive && attempt.sharing == oboe::SharingMode::Exclusive) {
            continue;
        }

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(attempt.sharing)
                ->setFormat(oboe::AudioFormat::I16)
                ->setChannelCount(kOutputChannels)
                ->setSampleRate(attempt.sampleRate)
                ->setUsage(oboe::Usage::Game)
                ->setDataCallback(this)
                ->setErrorCallback(this);

        // 容量下限只在共享模式要：独占（MMAP）的缓冲由驱动固定，硬要容量反而会让
        // openStream 直接失败，白白丢掉独占的机会。共享模式才是我们能改缓冲的地方。
        if (attempt.sharing == oboe::SharingMode::Shared) {
            builder.setBufferCapacityInFrames(kMinBufferCapacityFrames);
        }

        result = builder.openStream(_stream);
        attemptsUsed++;
        _openResult.store(static_cast<int32_t>(result));

        if (result == oboe::Result::OK && _stream) {
            usedLabel = attempt.label;
            break;
        }

        LOGW("openStream %s failed: %s (code %d)", attempt.label, oboe::convertToText(result),
             static_cast<int>(result));

        if (attempt.sharing == oboe::SharingMode::Exclusive) {
            _exclusiveDeniedCode.store(static_cast<int32_t>(result));
        }

        _stream.reset();
    }

    _openAttempts.store(attemptsUsed);

    if (result != oboe::Result::OK || !_stream) {
        _lastErrorCode.store(static_cast<int32_t>(result));
        _started.store(0);
        LOGW("openStream failed: %s (code %d)", oboe::convertToText(result), static_cast<int>(result));
        return static_cast<int32_t>(result);
    }

    // 核对实际参数：Oboe/AAudio 可能悄悄改掉请求值，这里全部按实测值走，
    // 并把差异通过 stats 报给 UI（要求 3.2 第三项）。
    const int32_t actualRate = _stream->getSampleRate();
    const int32_t actualChannels = _stream->getChannelCount();
    const int32_t burst = _stream->getFramesPerBurst();
    const oboe::SharingMode sharing = _stream->getSharingMode();
    const oboe::AudioFormat format = _stream->getFormat();
    const int32_t capacity = _stream->getBufferCapacityInFrames();

    _actualSampleRate.store(actualRate);
    _actualChannels.store(actualChannels);
    _framesPerBurst.store(burst);
    _sharingMode.store(sharing == oboe::SharingMode::Exclusive ? 0 : 1);
    // 设备实际采样率可能与 48k 不同，此时每输出一帧要消耗 48000/actualRate 个流帧
    _baseStep.store(actualRate > 0 ? 48000.0 / static_cast<double>(actualRate) : 1.0);

    if (actualChannels != kOutputChannels || format != oboe::AudioFormat::I16) {
        LOGW("stream params differ: %d ch / %d bit (requested %d ch / 16 bit)",
             actualChannels, static_cast<int>(format), kOutputChannels);
    }

    // ---- Oboe 内部缓冲：2 个 burst ----
    // AAudio 保证回调尺寸 <= burst，所以 2 个 burst 就是官方推荐的低延迟起点。
    // 早期版本压到 4 个 burst（≈21 ms）是基于“回调可能一次取整个缓冲”的错误假设；
    // 现在把真实回调尺寸暴露成 kMaxCallbackFrames，可随时证伪。
    const int32_t rateForMs = actualRate > 0 ? actualRate : _requestedSampleRate;
    int32_t desired = burst * kBurstsPerBuffer;
    if (capacity > 0) {
        desired = std::min(desired, capacity);
    }

    const oboe::ResultWithValue<int32_t> bufferResult = _stream->setBufferSizeInFrames(desired);
    const oboe::Result setBufferStatus = bufferResult;
    const int32_t bufferSize = _stream->getBufferSizeInFrames();
    _bufferSetResult.store(static_cast<int32_t>(setBufferStatus));
    _bufferSizeFrames.store(bufferSize);
    _bufferCapacityFrames.store(capacity);

    if (setBufferStatus != oboe::Result::OK) {
        // 独占（MMAP）下缓冲常由驱动固定，不允许应用改；不影响功能，只影响余量
        LOGW("setBufferSizeInFrames(%d) -> %s (code %d, value %d); buffer stays %d frames",
             desired, oboe::convertToText(setBufferStatus), static_cast<int>(setBufferStatus),
             bufferResult.value(), bufferSize);
    }

    result = _stream->requestStart();
    if (result != oboe::Result::OK) {
        _lastErrorCode.store(static_cast<int32_t>(result));
        _started.store(0);
        LOGW("requestStart failed: %s (code %d)", oboe::convertToText(result),
             static_cast<int>(result));
        _stream.reset();
        return static_cast<int32_t>(result);
    }

    _lastCallbackNanos.store(nowNanos());
    _maxCallbackGapNanos.store(0);
    _errorFlag.store(0);
    _started.store(1);
    _streamGeneration.fetch_add(1);

    LOGI("stream up: %s | sharing=%s | %d Hz %d ch | burst=%d cap=%d size=%d (%.1f ms) | setBuffer=%s",
         usedLabel,
         sharing == oboe::SharingMode::Exclusive ? "exclusive" : "shared",
         actualRate, actualChannels, burst, capacity, bufferSize,
         rateForMs > 0 ? bufferSize * 1000.0f / static_cast<float>(rateForMs) : 0.0f,
         oboe::convertToText(setBufferStatus));
    return 0;
}

void AudioEngine::closeStreamLocked() {
    if (!_stream) {
        _started.store(0);
        return;
    }

    _started.store(0);
    _stream->requestStop();
    _stream->close();
    _stream.reset();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData, int32_t numFrames) {
    auto* out = static_cast<int16_t*>(audioData);
    const int64_t now = nowNanos();
    const int64_t previous = _lastCallbackNanos.exchange(now, std::memory_order_relaxed);
    if (previous > 0) {
        const int64_t gap = now - previous;
        if (gap > _maxCallbackGapNanos.load(std::memory_order_relaxed)) {
            _maxCallbackGapNanos.store(gap, std::memory_order_relaxed);
        }
    }

    _callbackCount.fetch_add(1, std::memory_order_relaxed);

    // 验证 AAudio 的回调尺寸是否 <= burst（Claude 指出的检查点）
    if (numFrames > _maxCallbackFrames.load(std::memory_order_relaxed)) {
        _maxCallbackFrames.store(numFrames, std::memory_order_relaxed);
    }

    // 闭环控制量：回调入口处的水位最小值。
    // PC 端成批投递时水位是锯齿，控制最小值才有物理意义（平均值会高估约半个批次）。
    {
        const int64_t water = static_cast<int64_t>(_ring.writeFrame()) -
                              static_cast<int64_t>(std::floor(_readPos));
        int64_t current = _waterMinSinceRead.load(std::memory_order_relaxed);
        while (water < current &&
               !_waterMinSinceRead.compare_exchange_weak(current, water, std::memory_order_relaxed)) {
        }
    }

    readFrames(out, numFrames);
    mixBeep(out, numFrames);

    _framesPlayed.fetch_add(static_cast<uint64_t>(numFrames), std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // 不在这里重开：Oboe 要求在另一条线程上重开。这里只置标志，
    // Kotlin 的控制协程会在几十毫秒内看到并调用 nativeReopen()。
    _lastErrorCode.store(static_cast<int32_t>(error));
    _errorFlag.store(1, std::memory_order_release);
    _started.store(0);
    LOGW("onErrorAfterClose: %s", oboe::convertToText(error));
}

void AudioEngine::onErrorBeforeClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGW("onErrorBeforeClose: %s", oboe::convertToText(error));
}

int32_t AudioEngine::readFrames(int16_t* out, int32_t frames) {
    const double step = _baseStep.load(std::memory_order_relaxed) * _ratio.load(std::memory_order_relaxed);
    const uint64_t writeHead = _ring.writeFrame();
    const int32_t target = _targetWaterFrames.load(std::memory_order_relaxed);

    // 生产者请求的重定位（断流重建 / 缓冲溢出）
    const int64_t requested = _requestedReadPos.exchange(-1, std::memory_order_relaxed);
    if (requested >= 0) {
        _readPos = static_cast<double>(requested);
        if (_readPos < 1.0) _readPos = 1.0;
        _fadeInRemaining = kFadeFrames;
    }

    if (_readPos > static_cast<double>(writeHead)) {
        _readPos = static_cast<double>(writeHead);
    }

    // ---- 启动垫水位 ----
    // 消费端如果从第 0 帧就开始读，水位会收敛到“刚好够一次回调”的临界点，而不是目标水位：
    // PHJ110 上实测水位恒为 ~10.2 ms 而目标 22.2 ms，于是每批包之间都欠载（2475 次，每次只差 0.3 ms）。
    // 新语义：target 现在表示**窗口最小值**的目标（而不是平均值），见 LatencyController；
    // 所以垫水位要多垫“一个发包批次”，让锯齿的低谷刚好落在 target 上。
    if (_prefillPending.load(std::memory_order_relaxed)) {
        const int32_t minTarget = target > 0 ? target : 960;
        const int32_t batch = _framesPerBurst.load(std::memory_order_relaxed) * 2;  // 一个批次 ≈ 2 burst
        const int32_t need = minTarget + (batch > 0 ? batch : 480);
        const int64_t level = static_cast<int64_t>(writeHead) - static_cast<int64_t>(_readPos);
        if (level < static_cast<int64_t>(need) + frames + 2) {
            std::memset(out, 0, static_cast<size_t>(frames) * 2 * sizeof(int16_t));
            _prefillFrames.fetch_add(static_cast<uint64_t>(frames), std::memory_order_relaxed);
            _ring.publishReadFrame(static_cast<uint64_t>(std::max(0.0, std::floor(_readPos))));
            return 0;  // 垫水位期间的静音不计入欠载
        }

        _prefillPending.store(false, std::memory_order_relaxed);
        LOGI("prefill done: water=%lld frames (min target %d)", static_cast<long long>(level), minTarget);
    }

    // 积压保护：超过 target + 3 个 burst 才丢帧，带迟滞（回到 target + 1 burst 以内才解除），
    // 避免在阈值附近反复丢帧。丢完做 2.5 ms 淡入。
    if (target > 0) {
        const int32_t burst = _framesPerBurst.load(std::memory_order_relaxed) > 0
                                  ? _framesPerBurst.load(std::memory_order_relaxed)
                                  : 240;
        const double level = static_cast<double>(writeHead) - _readPos;
        const double trigger = static_cast<double>(target) + 3.0 * burst;
        const double release = static_cast<double>(target) + 1.0 * burst;

        if (!_dropActive && level > trigger) {
            _dropActive = true;
        } else if (_dropActive && level < release) {
            _dropActive = false;
        }

        if (_dropActive) {
            const double skipTo = static_cast<double>(writeHead) - static_cast<double>(target);
            if (skipTo > _readPos) {
                _dropFrames.fetch_add(static_cast<uint64_t>(skipTo - _readPos), std::memory_order_relaxed);
                _dropEvents.fetch_add(1, std::memory_order_relaxed);
                _readPos = skipTo;
                _fadeInRemaining = kFadeFrames;
            }
        }
    }

    double pos = _readPos;
    int32_t produced = 0;

    for (; produced < frames; ++produced) {
        const int64_t index = static_cast<int64_t>(std::floor(pos));
        // 三次插值需要 index-1 .. index+2 四个点，且必须是已经写进来的数据
        if (index < 1 || static_cast<uint64_t>(index + 2) >= writeHead) {
            break;
        }

        const float frac = static_cast<float>(pos - static_cast<double>(index)) ;
        const float l = cubic(static_cast<float>(_ring.leftAt(static_cast<uint64_t>(index - 1))),
                              static_cast<float>(_ring.leftAt(static_cast<uint64_t>(index))),
                              static_cast<float>(_ring.leftAt(static_cast<uint64_t>(index + 1))),
                              static_cast<float>(_ring.leftAt(static_cast<uint64_t>(index + 2))),
                              frac);
        const float r = cubic(static_cast<float>(_ring.rightAt(static_cast<uint64_t>(index - 1))),
                              static_cast<float>(_ring.rightAt(static_cast<uint64_t>(index))),
                              static_cast<float>(_ring.rightAt(static_cast<uint64_t>(index + 1))),
                              static_cast<float>(_ring.rightAt(static_cast<uint64_t>(index + 2))),
                              frac);

        float gain = 1.0f;
        if (_fadeInRemaining > 0) {
            gain = 1.0f - (static_cast<float>(_fadeInRemaining) / static_cast<float>(kFadeFrames));
            _fadeInRemaining--;
        }

        out[produced * 2] = clamp16(l * gain);
        out[produced * 2 + 1] = clamp16(r * gain);
        pos += step;
    }

    if (produced < frames) {
        // 欠载：剩余部分补静音，且【不推进读位置】——样本事后不丢，
        // 水位上升后由重采样比率慢慢拉回来（scrcpy 式平滑水位）。
        std::memset(out + produced * 2, 0, static_cast<size_t>(frames - produced) * 2 * sizeof(int16_t));
        _underrunEvents.fetch_add(1, std::memory_order_relaxed);
        _underrunFrames.fetch_add(static_cast<uint64_t>(frames - produced), std::memory_order_relaxed);
    }

    _readPos = pos;
    _ring.publishReadFrame(static_cast<uint64_t>(std::max(0.0, std::floor(pos))));
    return produced;
}

void AudioEngine::mixBeep(int16_t* out, int32_t frames) {
    const int32_t remaining = _beepFramesRemaining.load(std::memory_order_relaxed);
    if (remaining <= 0) {
        return;
    }

    const int32_t take = std::min(frames, remaining);
    const double sampleRate = static_cast<double>(_actualSampleRate.load(std::memory_order_relaxed));
    const float phaseIncrement = static_cast<float>(2.0 * kPi * 1000.0 / (sampleRate > 0 ? sampleRate : 48000.0));

    for (int32_t i = 0; i < take; ++i) {
        const int32_t fromStart = kBeepFrames - remaining + i;
        const int32_t fromEnd = remaining - i;
        float envelope = 1.0f;
        if (fromStart < kBeepFadeFrames) {
            envelope = static_cast<float>(fromStart) / static_cast<float>(kBeepFadeFrames);
        }

        if (fromEnd < kBeepFadeFrames) {
            envelope = std::min(envelope, static_cast<float>(fromEnd) / static_cast<float>(kBeepFadeFrames));
        }

        const float sample = std::sin(_beepPhase) * kBeepAmplitude * envelope * 32767.0f;
        _beepPhase += phaseIncrement;
        if (_beepPhase > static_cast<float>(2.0 * kPi)) {
            _beepPhase -= static_cast<float>(2.0 * kPi);
        }

        const int32_t left = out[i * 2] + static_cast<int32_t>(sample);
        const int32_t right = out[i * 2 + 1] + static_cast<int32_t>(sample);
        out[i * 2] = clamp16(static_cast<float>(left));
        out[i * 2 + 1] = clamp16(static_cast<float>(right));
    }

    _beepFramesRemaining.store(remaining - take, std::memory_order_relaxed);
}

void AudioEngine::pushFrames(int64_t refFrameIndex, const int16_t* pcm, int32_t frames) {
    if (pcm == nullptr || frames <= 0 || refFrameIndex < 0) {
        return;
    }

    std::lock_guard<std::mutex> lock(_producerMutex);

    if (!_timelineStarted) {
        _timelineStarted = true;
        _expectedRef = static_cast<uint64_t>(refFrameIndex);
    }

    const uint64_t ref = static_cast<uint64_t>(refFrameIndex);

    if (ref > _expectedRef) {
        const uint64_t gap = ref - _expectedRef;
        if (gap > kMaxGapFrames) {
            // 断流太久：不补几秒钟静音（那会把水位顶爆），直接重建时间线，
            // 并重新垫水位——否则重建后又回到“刚好不欠载”的临界状态。
            _resyncEvents.fetch_add(1, std::memory_order_relaxed);
            _requestedReadPos.store(static_cast<int64_t>(_ring.writeFrame()), std::memory_order_release);
            _prefillPending.store(true, std::memory_order_relaxed);
        } else {
            // 缺包补静音：在静音洞两端各做 1 ms 淡出/淡入，避免硬切造成的咔哒声。
            const uint64_t gapStart = _ring.writeFrame();
            if (gapStart > static_cast<uint64_t>(kGapFadeFrames)) {
                _ring.fadeRange(gapStart - static_cast<uint64_t>(kGapFadeFrames), kGapFadeFrames, 1.0f, 0.0f);
            }

            _ring.writeSilence(static_cast<int32_t>(gap));
            _silenceFilled.fetch_add(gap, std::memory_order_relaxed);
            _pendingFadeIn = kGapFadeFrames;
        }

        _expectedRef = ref;
    }

    if (ref + static_cast<uint64_t>(frames) <= _expectedRef) {
        // 整包都落在“已写过”的区间内：可能是重复包/乱序，也可能是**发送端重启**
        // （PC 端引擎重建后 sampleCount 从 0 重新计数）。
        // 后者如果当成重复包丢掉，接收端就会空转到计数追上来——真机上表现为
        // 丢包 0、水位恒 0、目标被欠载顶到 60 ms，几分钟内完全没声音。
        // 用“落后超过一个最大缺口”来区分：这种情况按断流重建时间线。
        const uint64_t behind = _expectedRef - (ref + static_cast<uint64_t>(frames));
        if (behind <= kMaxGapFrames) {
            return;
        }

        _resyncEvents.fetch_add(1, std::memory_order_relaxed);
        _ring.clear();
        _requestedReadPos.store(0, std::memory_order_release);
        _prefillPending.store(true, std::memory_order_release);
        _expectedRef = ref;
    }

    const int32_t offset = static_cast<int32_t>(_expectedRef - ref);
    const int32_t count = frames - offset;
    if (count <= 0) {
        return;
    }

    const uint64_t writeHead = _ring.writeFrame();
    const uint64_t readHead = _ring.readFrame();
    if ((writeHead - readHead) + static_cast<uint64_t>(count) + 1024 > StereoRing::kCapacityFrames) {
        // 缓冲写满：丢掉这一包，并让读端跳到目标水位，下一包就能写进来
        _overflowDrops.fetch_add(static_cast<uint64_t>(count), std::memory_order_relaxed);
        const int32_t target = _targetWaterFrames.load(std::memory_order_relaxed);
        _requestedReadPos.store(static_cast<int64_t>(writeHead) - target, std::memory_order_release);
        return;
    }

    const uint64_t packetStart = _ring.writeFrame();
    _ring.writeInterleaved(pcm + offset * 2, count);

    // 补过静音之后，新数据的开头也要淡入，否则从静音切回音频会有咔哒声
    if (_pendingFadeIn > 0 && count > 0) {
        const int32_t fade = _pendingFadeIn < count ? _pendingFadeIn : count;
        _ring.fadeRange(packetStart, fade, 0.0f, 1.0f);
        _pendingFadeIn = 0;
    }

    _expectedRef += static_cast<uint64_t>(count);
}

void AudioEngine::fillStats(double* out, int32_t length) {
    if (out == nullptr || length < kStatsCount) {
        return;
    }

    std::memset(out, 0, sizeof(double) * static_cast<size_t>(kStatsCount));

    const uint64_t writeHead = _ring.writeFrame();
    const uint64_t readHead = _ring.readFrame();
    const uint64_t water = writeHead > readHead ? writeHead - readHead : 0;

    // Oboe 的计算延迟接口在 R 之前不建议在数据回调里调用，这里放在控制线程上
    {
        std::lock_guard<std::mutex> lock(_controlMutex);
        if (_stream) {
            const oboe::ResultWithValue<double> latency = _stream->calculateLatencyMillis();
            if (latency) {
                _latencyUsec.store(static_cast<int32_t>(latency.value() * 1000.0));
            }
        }
    }

    out[kWaterFrames] = static_cast<double>(water);
    out[kTargetWaterFrames] = static_cast<double>(_targetWaterFrames.load(std::memory_order_relaxed));
    out[kFramesPlayed] = static_cast<double>(_framesPlayed.load(std::memory_order_relaxed));
    out[kUnderrunEvents] = static_cast<double>(_underrunEvents.load(std::memory_order_relaxed));
    out[kUnderrunFrames] = static_cast<double>(_underrunFrames.load(std::memory_order_relaxed));
    out[kDropEvents] = static_cast<double>(_dropEvents.load(std::memory_order_relaxed));
    out[kDropFrames] = static_cast<double>(_dropFrames.load(std::memory_order_relaxed));
    out[kSilenceFilled] = static_cast<double>(_silenceFilled.load(std::memory_order_relaxed));
    out[kResyncEvents] = static_cast<double>(_resyncEvents.load(std::memory_order_relaxed));
    out[kSampleRate] = static_cast<double>(_actualSampleRate.load(std::memory_order_relaxed));
    out[kChannels] = static_cast<double>(_actualChannels.load(std::memory_order_relaxed));
    out[kFramesPerBurst] = static_cast<double>(_framesPerBurst.load(std::memory_order_relaxed));
    out[kSharingMode] = static_cast<double>(_sharingMode.load(std::memory_order_relaxed));
    out[kLatencyMs] = static_cast<double>(_latencyUsec.load(std::memory_order_relaxed)) / 1000.0;
    out[kStreamGeneration] = static_cast<double>(_streamGeneration.load(std::memory_order_relaxed));
    out[kErrorFlag] = static_cast<double>(_errorFlag.load(std::memory_order_relaxed));
    out[kLastErrorCode] = static_cast<double>(_lastErrorCode.load(std::memory_order_relaxed));
    out[kCallbackCount] = static_cast<double>(_callbackCount.load(std::memory_order_relaxed));
    out[kMaxCallbackGapMs] = static_cast<double>(_maxCallbackGapNanos.load(std::memory_order_relaxed)) / 1e6;
    out[kRatio] = _ratio.load(std::memory_order_relaxed);
    out[kWriteHead] = static_cast<double>(writeHead);
    out[kRingCapacity] = static_cast<double>(StereoRing::kCapacityFrames);
    out[kOverflowDrops] = static_cast<double>(_overflowDrops.load(std::memory_order_relaxed));
    out[kStarted] = static_cast<double>(_started.load(std::memory_order_relaxed));
    out[kBaseStep] = _baseStep.load(std::memory_order_relaxed);
    out[kOpenResult] = static_cast<double>(_openResult.load(std::memory_order_relaxed));
    out[kBufferSizeFrames] = static_cast<double>(_bufferSizeFrames.load(std::memory_order_relaxed));
    out[kBufferCapacityFrames] = static_cast<double>(_bufferCapacityFrames.load(std::memory_order_relaxed));
    out[kRequestedExclusive] = static_cast<double>(_requestedExclusive.load(std::memory_order_relaxed));
    out[kBufferSetResult] = static_cast<double>(_bufferSetResult.load(std::memory_order_relaxed));
    out[kOpenAttempts] = static_cast<double>(_openAttempts.load(std::memory_order_relaxed));
    out[kExclusiveDeniedCode] = static_cast<double>(_exclusiveDeniedCode.load(std::memory_order_relaxed));
    out[kPrefillFrames] = static_cast<double>(_prefillFrames.load(std::memory_order_relaxed));
    // 取走并重置区间最小值（读-改-写，控制线程每 50 ms 调一次）
    const int64_t waterMin = _waterMinSinceRead.exchange(INT64_MAX, std::memory_order_relaxed);
    out[kWaterMinFrames] = waterMin == INT64_MAX ? static_cast<double>(water) : static_cast<double>(waterMin);
    out[kMaxCallbackFrames] = static_cast<double>(_maxCallbackFrames.load(std::memory_order_relaxed));
    out[kPrefillActive] = _prefillPending.load(std::memory_order_relaxed) ? 1.0 : 0.0;
}

}  // namespace echolink
