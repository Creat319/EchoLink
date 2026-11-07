using System.Runtime.InteropServices;

namespace EchoLink.Core.Audio;

public enum SampleEncoding
{
    Unsupported,
    Pcm8,
    Pcm16,
    Pcm24,
    Pcm32,
    Float32,
}

/// <summary>
/// WASAPI 端点混音格式（WAVEFORMATEX / WAVEFORMATEXTENSIBLE）的解析结果。
/// 环回抓到的数据按此格式接收，再统一转换到 48k/16bit/立体声。
/// </summary>
public sealed class DeviceFormat
{
    private static readonly byte[] SubtypePcm =
    {
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71,
    };

    private static readonly byte[] SubtypeFloat =
    {
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71,
    };

    public ushort FormatTag { get; private init; }
    public int SampleRate { get; private init; }
    public int Channels { get; private init; }
    public int BitsPerSample { get; private init; }
    public int BlockAlign { get; private init; }
    public uint ChannelMask { get; private init; }
    public SampleEncoding Encoding { get; private init; }

    public bool IsStereo => Channels == 2;
    public bool Is48k => SampleRate == 48000;
    public bool NeedsResampling => SampleRate != 48000;

    public static DeviceFormat FromPointer(IntPtr waveFormat)
    {
        if (waveFormat == IntPtr.Zero)
        {
            throw new ArgumentNullException(nameof(waveFormat));
        }

        var tag = (ushort)Marshal.ReadInt16(waveFormat, 0);
        var channels = (ushort)Marshal.ReadInt16(waveFormat, 2);
        var sampleRate = Marshal.ReadInt32(waveFormat, 4);
        var blockAlign = (ushort)Marshal.ReadInt16(waveFormat, 12);
        var bits = (ushort)Marshal.ReadInt16(waveFormat, 14);

        var encoding = SampleEncoding.Unsupported;
        var channelMask = 0u;

        switch (tag)
        {
            case 1: // WAVE_FORMAT_PCM
                encoding = bits switch
                {
                    8 => SampleEncoding.Pcm8,
                    16 => SampleEncoding.Pcm16,
                    24 => SampleEncoding.Pcm24,
                    32 => SampleEncoding.Pcm32,
                    _ => SampleEncoding.Unsupported,
                };
                break;

            case 3: // WAVE_FORMAT_IEEE_FLOAT
                encoding = bits == 32 ? SampleEncoding.Float32 : SampleEncoding.Unsupported;
                break;

            case 0xFFFE: // WAVE_FORMAT_EXTENSIBLE
            {
                var cbSize = (ushort)Marshal.ReadInt16(waveFormat, 16);
                if (cbSize >= 22)
                {
                    channelMask = (uint)Marshal.ReadInt32(waveFormat, 20);
                    var guid = new byte[16];
                    Marshal.Copy(waveFormat + 24, guid, 0, 16);
                    var baseEncoding = guid.AsSpan().SequenceEqual(SubtypePcm)
                        ? SampleEncoding.Pcm16
                        : guid.AsSpan().SequenceEqual(SubtypeFloat)
                            ? SampleEncoding.Float32
                            : SampleEncoding.Unsupported;

                    // wBitsPerSample 是容器位宽；24-in-32 时按 Pcm32（左对齐）处理
                    encoding = baseEncoding switch
                    {
                        SampleEncoding.Pcm16 => bits switch
                        {
                            8 => SampleEncoding.Pcm8,
                            16 => SampleEncoding.Pcm16,
                            24 => SampleEncoding.Pcm24,
                            32 => SampleEncoding.Pcm32,
                            _ => SampleEncoding.Unsupported,
                        },
                        SampleEncoding.Float32 => bits == 32 ? SampleEncoding.Float32 : SampleEncoding.Unsupported,
                        _ => SampleEncoding.Unsupported,
                    };
                }

                break;
            }
        }

        if (blockAlign <= 0 && channels > 0 && bits > 0)
        {
            blockAlign = (ushort)(channels * ((bits + 7) / 8));
        }

        return new DeviceFormat
        {
            FormatTag = tag,
            SampleRate = sampleRate,
            Channels = channels,
            BitsPerSample = bits,
            BlockAlign = blockAlign,
            ChannelMask = channelMask,
            Encoding = encoding,
        };
    }

    public override string ToString()
        => $"{Encoding} {SampleRate}Hz {Channels}ch {BitsPerSample}bit (tag=0x{FormatTag:X4}, blockAlign={BlockAlign}, mask=0x{ChannelMask:X})";
}
