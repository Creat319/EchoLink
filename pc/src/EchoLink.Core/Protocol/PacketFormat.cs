using System.Buffers.Binary;

namespace EchoLink.Core.Protocol;

/// <summary>
/// 线格式常量。与 docs/protocol.md 一一对应，任何改动都必须同时改协议文档与安卓端 Protocol.kt。
/// </summary>
public static class PacketFormat
{
    public const int SampleRate = 48000;
    public const int Channels = 2;
    public const int BitsPerSample = 16;
    public const int BytesPerSample = BitsPerSample / 8;

    /// <summary>每包帧数：240 帧 @48k = 5.000 ms。</summary>
    public const int FramesPerPacket = 240;

    public const int BytesPerFrame = Channels * BytesPerSample;   // 4
    public const int PayloadBytes = FramesPerPacket * BytesPerFrame; // 960
    public const int HeaderBytes = 20;                             // v3：多了 4 字节 stream
    public const int PacketBytes = HeaderBytes + PayloadBytes;    // 980

    public const int SampleCountOffset = 4;
    public const int TimestampOffset = 12;

    /// <summary>流类型字段（协议 v3 起）。4 字节对齐，保持载荷对 int16 友好。</summary>
    public const int StreamOffset = 16;

    public const int PayloadOffset = HeaderBytes;

    /// <summary>下行：PC 系统音频 → 手机播放。</summary>
    public const uint StreamDownlink = 0;

    /// <summary>上行：手机麦克风 → PC 虚拟麦克风。</summary>
    public const uint StreamUplink = 1;

    /// <summary>上行麦克风的采样率与声道数（单声道，减少带宽与 CPU）。</summary>
    public const int MicSampleRate = 48000;
    public const int MicChannels = 1;
    public const int MicBytesPerFrame = MicChannels * BytesPerSample;   // 2

    /// <summary>上行分包：480 帧 = 10 ms = 960 B（AEC 也要求 10 ms 块）。</summary>
    public const int MicFramesPerPacket = 480;

    /// <summary>手机监听端口。</summary>
    public const int DefaultAudioPort = 47810;

    /// <summary>PC 绑定端口（同时是音频包源端口与回报包目的端口）。</summary>
    public const int DefaultReportPort = 47811;

    public const int PacketIntervalMs = 5;
    public const int PacketsPerSecond = 200;

    /// <summary>每帧对应的微秒数（48 kHz → 20.8333 µs）。</summary>
    public const double MicrosPerFrame = 1_000_000.0 / SampleRate;
}
