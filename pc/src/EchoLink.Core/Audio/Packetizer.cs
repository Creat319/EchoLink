using System.Buffers.Binary;
using System.Runtime.InteropServices;
using EchoLink.Core.Protocol;

namespace EchoLink.Core.Audio;

public interface IPacketSink
{
    void Send(ReadOnlySpan<byte> packet, uint sequence, ulong sampleCount);
}

/// <summary>
/// 把连续的 48 kHz 立体声 float 流切成固定 240 帧的裸 PCM 包。
/// 单包结构：[seq:u32][sampleCount:u64][timestamp:u32][PCM:960B]，见 docs/protocol.md。
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

    /// <summary>推入交错立体声 float 帧，凑满 240 帧就发一个包。</summary>
    public void Push(ReadOnlySpan<float> stereo, int frames)
    {
        var frame = 0;
        while (frame < frames)
        {
            var spaceFrames = PacketFormat.FramesPerPacket - (_pendingSamples / PacketFormat.Channels);
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
                Emit();
            }
        }
    }

    /// <summary>丢弃未凑满的尾包（停止时用，避免发出半包）。</summary>
    public void ResetPartial() => _pendingSamples = 0;

    private void Emit()
    {
        var header = new AudioPacketHeader(NextSequence, NextSampleCount, _timestampProvider(NextSampleCount));
        header.WriteTo(_packet);

        var pcm = _packet.AsSpan(PacketFormat.PayloadOffset, PacketFormat.PayloadBytes);
        MemoryMarshal.Cast<short, byte>(_pending.AsSpan()).CopyTo(pcm);

        _sink.Send(_packet, NextSequence, NextSampleCount);

        NextSequence++;
        NextSampleCount += PacketFormat.FramesPerPacket;
        _pendingSamples = 0;
        PacketsEmitted++;
    }
}
