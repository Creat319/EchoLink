#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>

namespace echolink {

// 单生产者单消费者（SPSC）无锁环形缓冲：
//   写端 = UDP 接收线程（每个包写 240 帧）
//   读端 = Oboe 数据回调（只读，不分配、不加锁、不做 IO）
// 绝对帧号寻址：帧 n 的槽位 = n & kMask，这样“缺失区间补静音”只要推进写指针即可保持时间线连续。
class StereoRing {
public:
    static constexpr uint32_t kCapacityFrames = 16384;  // 341 ms @48k
    static constexpr uint64_t kMask = kCapacityFrames - 1;

    void clear() {
        std::memset(_left, 0, sizeof(_left));
        std::memset(_right, 0, sizeof(_right));
        _writeFrame.store(0, std::memory_order_release);
        _readFrame.store(0, std::memory_order_release);
    }

    uint64_t writeFrame() const { return _writeFrame.load(std::memory_order_acquire); }

    uint64_t readFrame() const { return _readFrame.load(std::memory_order_acquire); }

    // 读端发布自己的读位置，供写端做溢出保护
    void publishReadFrame(uint64_t frame) { _readFrame.store(frame, std::memory_order_release); }

    // ---- 生产端 ----
    void writeSilence(int32_t frames) {
        uint64_t w = _writeFrame.load(std::memory_order_relaxed);
        for (int32_t i = 0; i < frames; ++i) {
            const uint64_t idx = (w + i) & kMask;
            _left[idx] = 0;
            _right[idx] = 0;
        }
        _writeFrame.store(w + frames, std::memory_order_release);
    }

    void writeInterleaved(const int16_t* src, int32_t frames) {
        uint64_t w = _writeFrame.load(std::memory_order_relaxed);
        for (int32_t i = 0; i < frames; ++i) {
            const uint64_t idx = (w + i) & kMask;
            _left[idx] = src[i * 2];
            _right[idx] = src[i * 2 + 1];
        }
        _writeFrame.store(w + frames, std::memory_order_release);
    }

    // ---- 消费端 ----
    inline int16_t leftAt(uint64_t index) const { return _left[index & kMask]; }
    inline int16_t rightAt(uint64_t index) const { return _right[index & kMask]; }

private:
    int16_t _left[kCapacityFrames];
    int16_t _right[kCapacityFrames];
    std::atomic<uint64_t> _writeFrame{0};
    std::atomic<uint64_t> _readFrame{0};
};

}  // namespace echolink
