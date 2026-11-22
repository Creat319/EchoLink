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

    /// <summary>控制命令：PC → 手机（目前只有音量）。</summary>
    public const uint StreamControl = 2;

    /// <summary>控制包载荷：8 字节 = [command:u32][value:f32]，故整包 28 字节。</summary>
    public const int ControlPayloadBytes = 8;
    public const int ControlPacketBytes = HeaderBytes + ControlPayloadBytes;   // 28

    /// <summary>控制命令号。</summary>
    public const uint ControlSetGain = 1;

    // ---------------------------------------------------------------- 局域网发现
    //
    // 无线（局域网）连接用：PC 往各网段广播地址发探测包，手机收到后单播回应答。
    // 复用同一个 20 B 包头，靠 stream = 3 区分。
    //
    // 为什么是 PC 先发：PC 这边有 Windows 防火墙，"手机主动广播"在 PC 眼里是陌生入站，
    // 默认被丢；由 PC 先发包、手机应答，回包至少是同一条流的回程，更容易过。
    // 但广播探测能不能收到回包仍取决于防火墙策略，所以手动填 IP 永远是保底方案。

    /// <summary>流类型：局域网发现。</summary>
    public const uint StreamDiscovery = 3;

    public const uint DiscoverRequest = 1;
    public const uint DiscoverReply = 2;

    /// <summary>应答里设备名的最大字节数（UTF-8），够放 "Pixel 8 Pro" 这类名字。</summary>
    public const int DiscoverNameMaxBytes = 48;

    /// <summary>请求：[command:u32][nonce:u32]，整包 28 B。</summary>
    public const int DiscoverRequestBytes = HeaderBytes + 8;

    /// <summary>应答：[command:u32][nonce:u32][nameLen:u32][name...]。</summary>
    public const int DiscoverReplyHeaderBytes = HeaderBytes + 12;

    public static void WriteDiscoverRequest(Span<byte> destination, uint nonce)
    {
        if (destination.Length < DiscoverRequestBytes)
        {
            throw new ArgumentException($"需要至少 {DiscoverRequestBytes} 字节", nameof(destination));
        }

        destination[..DiscoverRequestBytes].Clear();
        BinaryPrimitives.WriteUInt32LittleEndian(destination, nonce);   // seq 位置放 nonce，方便日志对账
        BinaryPrimitives.WriteUInt32LittleEndian(destination[StreamOffset..], StreamDiscovery);
        BinaryPrimitives.WriteUInt32LittleEndian(destination[HeaderBytes..], DiscoverRequest);
        BinaryPrimitives.WriteUInt32LittleEndian(destination[(HeaderBytes + 4)..], nonce);
    }

    /// <summary>解析应答。不是应答包时返回 false。</summary>
    public static bool TryReadDiscoverReply(ReadOnlySpan<byte> packet, out uint nonce, out string? name)
    {
        nonce = 0;
        name = null;

        if (packet.Length < DiscoverReplyHeaderBytes)
        {
            return false;
        }

        if (BinaryPrimitives.ReadUInt32LittleEndian(packet[StreamOffset..]) != StreamDiscovery ||
            BinaryPrimitives.ReadUInt32LittleEndian(packet[HeaderBytes..]) != DiscoverReply)
        {
            return false;
        }

        nonce = BinaryPrimitives.ReadUInt32LittleEndian(packet[(HeaderBytes + 4)..]);

        var nameLen = BinaryPrimitives.ReadUInt32LittleEndian(packet[(HeaderBytes + 8)..]);
        var available = packet.Length - DiscoverReplyHeaderBytes;
        // 名字长度不合法时仍然认为"应答有效"，只是没有名字——总比把整台设备丢掉强
        if (nameLen == 0 || nameLen > (uint)available || nameLen > DiscoverNameMaxBytes)
        {
            return true;
        }

        try
        {
            name = System.Text.Encoding.UTF8.GetString(
                packet.Slice(DiscoverReplyHeaderBytes, (int)nameLen));
        }
        catch (ArgumentException)
        {
            name = null;
        }

        return true;
    }

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

    /// <summary>
    /// 写一个控制包（28 B）：20 B 包头 + [command:u32][value:f32]。
    /// 复用音频包头，靠 stream = <see cref="StreamControl"/> 与音频区分。
    /// </summary>
    public static void WriteControl(Span<byte> destination, uint sequence, uint command, float value)
    {
        if (destination.Length < ControlPacketBytes)
        {
            throw new ArgumentException($"需要至少 {ControlPacketBytes} 字节", nameof(destination));
        }

        destination[..ControlPacketBytes].Clear();
        BinaryPrimitives.WriteUInt32LittleEndian(destination[0..], sequence);
        BinaryPrimitives.WriteUInt64LittleEndian(destination[4..], 0);   // 控制包没有采样计数
        BinaryPrimitives.WriteUInt32LittleEndian(destination[12..], 0); // 也没有时间戳
        BinaryPrimitives.WriteUInt32LittleEndian(destination[StreamOffset..], StreamControl);

        BinaryPrimitives.WriteUInt32LittleEndian(destination[HeaderBytes..], command);
        BinaryPrimitives.WriteSingleLittleEndian(destination[(HeaderBytes + 4)..], value);
    }
}
