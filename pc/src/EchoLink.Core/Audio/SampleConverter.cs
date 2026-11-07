using System.Runtime.InteropServices;

namespace EchoLink.Core.Audio;

/// <summary>
/// 设备混音格式 → 立体声 float（交错）转换，含声道下混。
/// 这是热路径代码：不分配、不抛异常、无分支外的边界检查。
/// </summary>
public static class SampleConverter
{
    /// <summary>
    /// 把 <paramref name="frames"/> 帧原始设备数据转成交错立体声 float。
    /// <paramref name="destination"/> 需要至少 frames*2 个 float。
    /// </summary>
    /// <returns>实际写入的帧数（等于 frames，除非格式不支持）</returns>
    public static int ToStereoFloat(DeviceFormat format, ReadOnlySpan<byte> source, int frames, Span<float> destination)
    {
        if (format.Encoding == SampleEncoding.Unsupported || format.Channels <= 0)
        {
            return 0;
        }

        var required = frames * format.BlockAlign;
        if (source.Length < required || destination.Length < frames * 2)
        {
            frames = Math.Min(frames, Math.Min(source.Length / Math.Max(1, format.BlockAlign), destination.Length / 2));
            if (frames <= 0)
            {
                return 0;
            }
        }

        // 常见快路径：float32 立体声
        if (format.Encoding == SampleEncoding.Float32 && format.Channels == 2)
        {
            var floats = MemoryMarshal.Cast<byte, float>(source[..(frames * 8)]);
            for (var i = 0; i < frames; i++)
            {
                destination[i * 2] = floats[i * 2];
                destination[i * 2 + 1] = floats[i * 2 + 1];
            }

            return frames;
        }

        if (format.Encoding == SampleEncoding.Pcm16 && format.Channels == 2)
        {
            var pcm = MemoryMarshal.Cast<byte, short>(source[..(frames * 4)]);
            const float scale = 1f / 32768f;
            for (var i = 0; i < frames; i++)
            {
                destination[i * 2] = pcm[i * 2] * scale;
                destination[i * 2 + 1] = pcm[i * 2 + 1] * scale;
            }

            return frames;
        }

        var bytesPerSample = format.BitsPerSample / 8;
        for (var i = 0; i < frames; i++)
        {
            var frameOffset = i * format.BlockAlign;
            float left;
            float right;

            switch (format.Channels)
            {
                case 1:
                    left = right = ReadSample(format, source, frameOffset);
                    break;
                case 2:
                    left = ReadSample(format, source, frameOffset);
                    right = ReadSample(format, source, frameOffset + bytesPerSample);
                    break;
                default:
                    MixSurround(format, source, frameOffset, bytesPerSample, out left, out right);
                    break;
            }

            destination[i * 2] = Clamp(left);
            destination[i * 2 + 1] = Clamp(right);
        }

        return frames;
    }

    /// <summary>
    /// 交错立体声 float → 设备格式字节。用于保活流写入测试音/点击声（必须与设备格式一致才能被环回抓到）。
    /// </summary>
    public static int FromStereoFloat(DeviceFormat format, ReadOnlySpan<float> source, int frames, Span<byte> destination)
    {
        if (format.Encoding is not (SampleEncoding.Float32 or SampleEncoding.Pcm16 or SampleEncoding.Pcm32)
            || format.Channels != 2)
        {
            return 0;
        }

        var needed = frames * format.BlockAlign;
        if (destination.Length < needed)
        {
            return 0;
        }

        switch (format.Encoding)
        {
            case SampleEncoding.Float32:
            {
                var floats = MemoryMarshal.Cast<byte, float>(destination[..(frames * 8)]);
                for (var i = 0; i < frames * 2; i++)
                {
                    floats[i] = source[i];
                }

                break;
            }

            case SampleEncoding.Pcm16:
            {
                var pcm = MemoryMarshal.Cast<byte, short>(destination[..(frames * 4)]);
                for (var i = 0; i < frames * 2; i++)
                {
                    pcm[i] = (short)Math.Clamp(source[i] * 32767f, short.MinValue, short.MaxValue);
                }

                break;
            }

            case SampleEncoding.Pcm32:
            {
                var pcm = MemoryMarshal.Cast<byte, int>(destination[..(frames * 8)]);
                for (var i = 0; i < frames * 2; i++)
                {
                    pcm[i] = (int)Math.Clamp((double)source[i] * 2147483647d, int.MinValue, int.MaxValue);
                }

                break;
            }
        }

        return frames;
    }

    private static float ReadSample(DeviceFormat format, ReadOnlySpan<byte> source, int offset) => format.Encoding switch
    {
        SampleEncoding.Pcm8 => (source[offset] - 128) / 128f,
        SampleEncoding.Pcm16 => BitConverter.ToInt16(source.Slice(offset, 2)) / 32768f,
        SampleEncoding.Pcm24 => ((source[offset] | (source[offset + 1] << 8) | (source[offset + 2] << 16)) << 8) / 2147483648f,
        SampleEncoding.Pcm32 => BitConverter.ToInt32(source.Slice(offset, 4)) / 2147483648f,
        SampleEncoding.Float32 => BitConverter.ToSingle(source.Slice(offset, 4)),
        _ => 0f,
    };

    /// <summary>
    /// WAVE 声道顺序下混成立体声：FL,FR,FC,LFE,BL,BR,(SL,SR)。
    /// 中置/环绕按 -3 dB（0.7071）折叠，LFE 丢弃（避免低频过载）。
    /// </summary>
    private static void MixSurround(DeviceFormat format, ReadOnlySpan<byte> source, int frameOffset, int bytesPerSample, out float left, out float right)
    {
        const float fold = 0.70710678f;
        var channels = format.Channels;

        left = SampleChannel(format, source, frameOffset, bytesPerSample, 0, channels);
        right = SampleChannel(format, source, frameOffset, bytesPerSample, 1, channels);

        switch (channels)
        {
            case 2:
                break;

            case 3: // FL,FR,FC
            {
                var center = SampleChannel(format, source, frameOffset, bytesPerSample, 2, channels) * fold;
                left += center;
                right += center;
                break;
            }

            case 4: // FL,FR,BL,BR
                left += SampleChannel(format, source, frameOffset, bytesPerSample, 2, channels) * fold;
                right += SampleChannel(format, source, frameOffset, bytesPerSample, 3, channels) * fold;
                break;

            case 5: // FL,FR,FC,BL,BR
            {
                var center = SampleChannel(format, source, frameOffset, bytesPerSample, 2, channels) * fold;
                left += center + (SampleChannel(format, source, frameOffset, bytesPerSample, 3, channels) * fold);
                right += center + (SampleChannel(format, source, frameOffset, bytesPerSample, 4, channels) * fold);
                break;
            }

            case 6: // FL,FR,FC,LFE,BL,BR
            {
                var center = SampleChannel(format, source, frameOffset, bytesPerSample, 2, channels) * fold;
                left += center + (SampleChannel(format, source, frameOffset, bytesPerSample, 4, channels) * fold);
                right += center + (SampleChannel(format, source, frameOffset, bytesPerSample, 5, channels) * fold);
                break;
            }

            case 7: // FL,FR,FC,LFE,BC,SL,SR
            {
                var center = SampleChannel(format, source, frameOffset, bytesPerSample, 2, channels) * fold;
                var back = SampleChannel(format, source, frameOffset, bytesPerSample, 4, channels) * fold;
                left += center + back + (SampleChannel(format, source, frameOffset, bytesPerSample, 5, channels) * fold);
                right += center + back + (SampleChannel(format, source, frameOffset, bytesPerSample, 6, channels) * fold);
                break;
            }

            default: // 7.1: FL,FR,FC,LFE,BL,BR,SL,SR
            {
                var center = SampleChannel(format, source, frameOffset, bytesPerSample, 2, channels) * fold;
                left += center
                        + (SampleChannel(format, source, frameOffset, bytesPerSample, 4, channels) * fold)
                        + (SampleChannel(format, source, frameOffset, bytesPerSample, 6, channels) * fold);
                right += center
                         + (SampleChannel(format, source, frameOffset, bytesPerSample, 5, channels) * fold)
                         + (SampleChannel(format, source, frameOffset, bytesPerSample, 7, channels) * fold);
                break;
            }
        }
    }

    private static float SampleChannel(DeviceFormat format, ReadOnlySpan<byte> source, int frameOffset, int bytesPerSample, int channel, int channels)
    {
        if (channel >= channels)
        {
            return 0f;
        }

        var offset = frameOffset + (channel * bytesPerSample);
        return offset + bytesPerSample <= source.Length ? ReadSample(format, source, offset) : 0f;
    }

    private static float Clamp(float value) => value switch
    {
        > 1f => 1f,
        < -1f => -1f,
        _ => value,
    };
}
