using System.Buffers.Binary;
using System.Runtime.InteropServices;
using EchoLink.Core.Protocol;

namespace EchoLink.Core.Audio;

public interface IPacketSink
{
    void Send(ReadOnlySpan<byte> packet, uint sequence, ulong sampleCount);
}

/// <summary>
/// 把连续的 48 kHz 立体声 float 流切成裸 PCM 包。
/// 单包结构：[seq:u32][sampleCount:u64][timestamp:u32][PCM]（长度可变），见 docs/protocol.md。
///
/// 包长是**可变的**，最多 <see cref="PacketFormat.FramesPerPacket"/> 帧：
/// 抓取回调给多少就发多少（S/PDIF 端点是每回调 128 帧 → 每包 2.67 ms）。
/// 早期版本固定 240 帧，在 2.67 ms 投递粒度下会让播放等到"包内最老的一帧"，
/// 白白多等 0–5 ms。
/// </summary>
public sealed class Packetizer
{
    private readonly short[] _pending = new short[PacketFormat.FramesPerPacket * PacketFormat.Channels];
    private readonly byte[] _packet = new byte[PacketFormat.PacketBytes];
    private readonly IPacketSink _sink;
    private readonly Func<ulong, uint> _timestampProvider;

    private int _pendingSamples;

    public Packetizer(IPacketSink sink, Func<ulong, uint> timestampProvider)
    {
        _sink = sink;
        _timestampProvider = timestampProvider;
    }

    /// <summary>下一个包的序号。</summary>
    public uint NextSequence { get; set; }

    /// <summary>下一个包首帧的累计采样数。</summary>
    public ulong NextSampleCount { get; set; }

    public int PendingFrames => _pendingSamples / PacketFormat.Channels;

    public long PacketsEmitted { get; private set; }

    /// <summary>推入交错立体声 float 帧；攒满 <see cref="PacketFormat.FramesPerPacket"/> 就发一个整包。</summary>
    public void Push(ReadOnlySpan<float> stereo, int frames)
    {
        var frame = 0;
        while (frame < frames)
        {
            var pendingFrames = _pendingSamples / PacketFormat.Channels;
            var spaceFrames = PacketFormat.FramesPerPacket - pendingFrames;
            var take = Math.Min(spaceFrames, frames - frame);
            var sourceOffset = frame * PacketFormat.Channels;

            for (var i = 0; i < take * PacketFormat.Channels; i++)
            {
                var value = stereo[sourceOffset + i];
                _pending[_pendingSamples + i] = value switch
                {
                    > 1f => 32767,
                    < -1f => -32768,
                    _ => (short)(value * 32767f),
                };
            }

            _pendingSamples += take * PacketFormat.Channels;
            frame += take;

            if (_pendingSamples == _pending.Length)
            {
                Emit(PacketFormat.FramesPerPacket);
            }
        }
    }

    /// <summary>
    /// 把当前缓冲里不足一包的尾巴立刻发出去（每次抓取回调结束后调用）。
    /// 这样包长与抓取粒度对齐，而不是固定等 240 帧。
    /// </summary>
    public void Flush()
    {
        var frames = _pendingSamples / PacketFormat.Channels;
        if (frames > 0)
        {
            Emit(frames);
        }
    }

    /// <summary>丢弃未发出的尾包（停止时用）。</summary>
    public void ResetPartial() => _pendingSamples = 0;

    private void Emit(int frames)
    {
        var header = new AudioPacketHeader(NextSequence, NextSampleCount, _timestampProvider(NextSampleCount));
        header.WriteTo(_packet);

        var payloadBytes = frames * PacketFormat.BytesPerFrame;
        var pcm = _packet.AsSpan(PacketFormat.PayloadOffset, payloadBytes);
        MemoryMarshal.Cast<short, byte>(_pending.AsSpan(0, frames * PacketFormat.Channels)).CopyTo(pcm);

        _sink.Send(_packet.AsSpan(0, PacketFormat.HeaderBytes + payloadBytes), NextSequence, NextSampleCount);

        NextSequence++;
        NextSampleCount += (ulong)frames;
        _pendingSamples = 0;
        PacketsEmitted++;
    }
}
