namespace EchoLink.Core.Audio;

/// <summary>
/// 流式多相重采样器：把设备采样率统一到 48 kHz。
/// </summary>
/// <remarks>
/// 设计取舍：
/// <list type="bullet">
/// <item>不用 NAudio 的 pull 模型（需要额外线程 + 内部缓冲，会引入 1 个 burst 的额外延迟），
/// 这里是纯 push/pull 的原地处理，调用方（抓取线程）自己控制节奏。</item>
/// <item>24 抽头 Kaiser 窗 sinc + 256 相查表 + 相位线性插值：CPU 占用约 1%~2% 单核，</item>
/// <item>降采样时按 ratio 收缩截止频率，避免 96k→48k 混叠。</item>
/// <item>表内每个相位都做过归一化，保证直流增益为 1，不引入音量跳变。</item>
/// </list>
/// 起始会有 taps/2 帧（约 0.25 ms）的预热，属可接受误差。
/// </remarks>
public sealed class StreamingResampler
{
    private const int Taps = 24;
    private const int Phases = 256;
    private const double KaiserBeta = 7.0;

    private readonly int _inRate;
    private readonly int _outRate;
    private readonly bool _bypass;
    private readonly float[] _table;          // Phases * Taps
    private float[] _history;                 // 交错立体声
    private int _historyFrames;
    private double _position;                  // 相对 _history 起始的帧位置

    public StreamingResampler(int inputSampleRate, int outputSampleRate = 48000, int channels = 2)
    {
        if (inputSampleRate <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(inputSampleRate));
        }

        if (channels != 2)
        {
            throw new ArgumentException("only stereo is supported", nameof(channels));
        }

        _inRate = inputSampleRate;
        _outRate = outputSampleRate;
        _bypass = inputSampleRate == outputSampleRate;
        _history = new float[(Taps * 4 * 2) + (inputSampleRate / 2 * 2)];
        _table = _bypass ? Array.Empty<float>() : BuildTable(inputSampleRate, outputSampleRate);
    }

    public int InputSampleRate => _inRate;

    public int OutputSampleRate => _outRate;

    /// <summary>输入 <paramref name="frames"/> 帧立体声，输出到 <paramref name="output"/>，返回输出帧数。</summary>
    public int Process(ReadOnlySpan<float> input, int frames, Span<float> output)
    {
        if (frames <= 0)
        {
            return 0;
        }

        if (_bypass)
        {
            var count = Math.Min(frames, output.Length / 2);
            input[..(count * 2)].CopyTo(output);
            return count;
        }

        EnsureCapacity(_historyFrames + frames);
        input[..(frames * 2)].CopyTo(_history.AsSpan(_historyFrames * 2));
        _historyFrames += frames;

        var step = (double)_inRate / _outRate;
        var half = Taps / 2;
        var produced = 0;
        var maxOut = output.Length / 2;

        while (produced < maxOut && _position + half < _historyFrames)
        {
            var baseIndex = (int)Math.Floor(_position) - (half - 1);
            if (baseIndex < 0)
            {
                // 预热阶段：位置还没走到第一个完整窗口，补零推进
                _position += step;
                continue;
            }

            var frac = _position - Math.Floor(_position);
            var phasePosition = frac * Phases;
            var phaseIndex = (int)phasePosition;
            var alpha = (float)(phasePosition - phaseIndex);
            var row0 = (phaseIndex % Phases) * Taps;
            var row1 = ((phaseIndex + 1) % Phases) * Taps;

            float left = 0f;
            float right = 0f;
            var sampleIndex = baseIndex * 2;

            for (var tap = 0; tap < Taps; tap++)
            {
                var weight = _table[row0 + tap] + ((_table[row1 + tap] - _table[row0 + tap]) * alpha);
                left += _history[sampleIndex] * weight;
                right += _history[sampleIndex + 1] * weight;
                sampleIndex += 2;
            }

            output[produced * 2] = left;
            output[produced * 2 + 1] = right;
            produced++;
            _position += step;
        }

        Compact();
        return produced;
    }

    /// <summary>估算给定输入帧数最多能产出多少输出帧（用于预分配缓冲）。</summary>
    public int EstimateMaxOutput(int inputFrames)
        => _bypass ? inputFrames : (int)Math.Ceiling(inputFrames * (double)_outRate / _inRate) + 4;

    private void EnsureCapacity(int frames)
    {
        var needed = frames * 2;
        if (needed <= _history.Length)
        {
            return;
        }

        var grown = new float[Math.Max(needed, _history.Length * 2)];
        Array.Copy(_history, grown, _historyFrames * 2);
        _history = grown;
    }

    private void Compact()
    {
        var keepFromFrame = (int)Math.Floor(_position) - (Taps / 2) - 1;
        if (keepFromFrame <= 0)
        {
            return;
        }

        keepFromFrame = Math.Min(keepFromFrame, _historyFrames);
        var remaining = _historyFrames - keepFromFrame;
        if (remaining > 0)
        {
            Array.Copy(_history, keepFromFrame * 2, _history, 0, remaining * 2);
        }

        _historyFrames = remaining;
        _position -= keepFromFrame;
        if (_position < 0)
        {
            _position = 0;
        }
    }

    private static float[] BuildTable(int inputRate, int outputRate)
    {
        var table = new float[Phases * Taps];
        var cutoff = Math.Min(1.0, (double)outputRate / inputRate) * 0.97;

        for (var phase = 0; phase < Phases; phase++)
        {
            var frac = (double)phase / Phases;
            var sum = 0.0;
            var row = phase * Taps;

            for (var tap = 0; tap < Taps; tap++)
            {
                var distance = frac + ((Taps / 2) - 1) - tap;
                var value = WindowedSinc(distance, cutoff);
                table[row + tap] = (float)value;
                sum += value;
            }

            if (Math.Abs(sum) > 1e-9)
            {
                var normalizer = (float)(1.0 / sum);
                for (var tap = 0; tap < Taps; tap++)
                {
                    table[row + tap] *= normalizer;
                }
            }
        }

        return table;
    }

    private static double WindowedSinc(double distance, double cutoff)
    {
        var scaled = cutoff * distance;
        var sinc = Math.Abs(scaled) < 1e-9 ? 1.0 : Math.Sin(Math.PI * scaled) / (Math.PI * scaled);

        var normalized = Math.Abs(distance) / (Taps / 2.0);
        if (normalized >= 1.0)
        {
            return 0.0;
        }

        return cutoff * sinc * BesselI0(KaiserBeta * Math.Sqrt(1.0 - (normalized * normalized))) / BesselI0(KaiserBeta);
    }

    private static double BesselI0(double x)
    {
        var sum = 1.0;
        var term = 1.0;
        var half = x / 2.0;
        for (var k = 1; k < 24; k++)
        {
            term *= (half / k) * (half / k);
            sum += term;
            if (term < 1e-12 * sum)
            {
                break;
            }
        }

        return sum;
    }
}
