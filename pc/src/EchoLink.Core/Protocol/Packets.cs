using System.Buffers.Binary;

namespace EchoLink.Core.Protocol;

/// <summary>音频包（PC → 手机），976 字节。</summary>
public readonly struct AudioPacketHeader
{
    public AudioPacketHeader(uint sequence, ulong sampleCount, uint timestampMicros)
    {
        Sequence = sequence;
        SampleCount = sampleCount;
        TimestampMicros = timestampMicros;
    }

    public uint Sequence { get; }
    public ulong SampleCount { get; }
    public uint TimestampMicros { get; }

    /// <summary>写入 16 字节包头。</summary>
    public void WriteTo(Span<byte> destination)
    {
        if (destination.Length < PacketFormat.HeaderBytes)
        {
            throw new ArgumentException("header buffer too small", nameof(destination));
        }

        BinaryPrimitives.WriteUInt32LittleEndian(destination, Sequence);
        BinaryPrimitives.WriteUInt64LittleEndian(destination[PacketFormat.SampleCountOffset..], SampleCount);
        BinaryPrimitives.WriteUInt32LittleEndian(destination[PacketFormat.TimestampOffset..], TimestampMicros);
    }

    public static bool TryRead(ReadOnlySpan<byte> packet, out AudioPacketHeader header)
    {
        // 包长可变（跟随抓取粒度），只要求至少容纳包头
        if (packet.Length < PacketFormat.HeaderBytes)
        {
            header = default;
            return false;
        }

        var sequence = BinaryPrimitives.ReadUInt32LittleEndian(packet);
        var sampleCount = BinaryPrimitives.ReadUInt64LittleEndian(packet[PacketFormat.SampleCountOffset..]);
        var timestamp = BinaryPrimitives.ReadUInt32LittleEndian(packet[PacketFormat.TimestampOffset..]);
        header = new AudioPacketHeader(sequence, sampleCount, timestamp);
        return true;
    }

    public override string ToString() => $"seq={Sequence} frames={SampleCount} ts={TimestampMicros}us";
}

/// <summary>状态回报包（手机 → PC），40 字节。PC 侧只用于显示与诊断，不参与音频链路。</summary>
public readonly struct PhoneReport
{
    public const uint Magic = 0x50524C45; // 'E','L','R','P'
    public const int Size = 40;
    public const ushort CurrentVersion = 1;
    public const ushort TypeStatus = 1;

    public ushort Version { get; init; }
    public ushort Type { get; init; }
    public uint ReportSeq { get; init; }
    public ulong FramesPlayed { get; init; }
    public uint PacketsReceived { get; init; }
    public uint PacketsLost { get; init; }
    public uint Underruns { get; init; }
    public ushort WaterMs { get; init; }
    public ushort TargetWaterMs { get; init; }
    public ushort JitterP99Us { get; init; }
    public ushort LatencyMs { get; init; }
    public DateTimeOffset ReceivedAt { get; init; }

    public static bool TryParse(ReadOnlySpan<byte> buffer, DateTimeOffset receivedAt, out PhoneReport report)
    {
        report = default;
        if (buffer.Length < Size)
        {
            return false;
        }

        if (BinaryPrimitives.ReadUInt32LittleEndian(buffer) != Magic)
        {
            return false;
        }

        report = new PhoneReport
        {
            Version = BinaryPrimitives.ReadUInt16LittleEndian(buffer[4..]),
            Type = BinaryPrimitives.ReadUInt16LittleEndian(buffer[6..]),
            ReportSeq = BinaryPrimitives.ReadUInt32LittleEndian(buffer[8..]),
            FramesPlayed = BinaryPrimitives.ReadUInt64LittleEndian(buffer[12..]),
            PacketsReceived = BinaryPrimitives.ReadUInt32LittleEndian(buffer[20..]),
            PacketsLost = BinaryPrimitives.ReadUInt32LittleEndian(buffer[24..]),
            Underruns = BinaryPrimitives.ReadUInt32LittleEndian(buffer[28..]),
            WaterMs = BinaryPrimitives.ReadUInt16LittleEndian(buffer[32..]),
            TargetWaterMs = BinaryPrimitives.ReadUInt16LittleEndian(buffer[34..]),
            JitterP99Us = BinaryPrimitives.ReadUInt16LittleEndian(buffer[36..]),
            LatencyMs = BinaryPrimitives.ReadUInt16LittleEndian(buffer[38..]),
            ReceivedAt = receivedAt,
        };
        return true;
    }
}
