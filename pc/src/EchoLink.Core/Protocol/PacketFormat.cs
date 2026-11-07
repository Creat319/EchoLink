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
    public const int HeaderBytes = 16;
    public const int PacketBytes = HeaderBytes + PayloadBytes;    // 976

    public const int SampleCountOffset = 4;
    public const int TimestampOffset = 12;
    public const int PayloadOffset = HeaderBytes;

    /// <summary>手机监听端口。</summary>
    public const int DefaultAudioPort = 47810;

    /// <summary>PC 绑定端口（同时是音频包源端口与回报包目的端口）。</summary>
    public const int DefaultReportPort = 47811;

    public const int PacketIntervalMs = 5;
    public const int PacketsPerSecond = 200;

    /// <summary>每帧对应的微秒数（48 kHz → 20.8333 µs）。</summary>
    public const double MicrosPerFrame = 1_000_000.0 / SampleRate;
}
