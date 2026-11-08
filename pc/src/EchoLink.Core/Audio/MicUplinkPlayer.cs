using System.Runtime.InteropServices;
using EchoLink.Core.Protocol;
using EchoLink.Core.Wasapi;

namespace EchoLink.Core.Audio;

/// <summary>上行麦克风的落点：收到帧就推进来（由 UDP 接收线程调用）。</summary>
public interface IUplinkSink
{
    void PushMic(ReadOnlySpan<short> mono, int frames);
}

/// <summary>
/// 把手机上传的麦克风数据播放到 PC 的一个录音端点（通常是 VB-Cable 的 CABLE Input，
/// 会议软件再把 CABLE Output 当麦克风）。
///
/// 与下行是镜像结构，但方向反过来：
/// - 写入端是手机 ADC 的时钟，读出端是 CABLE 设备时钟，两者不同源，必须做慢速重采样
/// - 用一个分数读指针 + 线性插值实现"变速率读取"，比率在 ±0.5% 内由水位闭环调节
///
/// 为什么不用把 48k 固定转成设备率再送：固定比率消化不了两端的时钟偏差，
/// 几分钟后水位就会单调漂移到底或顶到溢出。
/// </summary>
public sealed class MicUplinkPlayer : IDisposable, IUplinkSink
{
    private const int RingFrames = 48_000 * 2;          // 2 秒，够吸收几秒的停顿
    private const int InitialWaterMs = 60;              // 起步水位，宁可先大后收
    private const int TargetWaterMs = 40;               // 稳态目标
    private const int MaxWaterMs = 400;                 // 超过就直接丢弃（长时间断流后的积压）
    private const double MaxRatio = 0.005;              // ±0.5%，人耳对麦克风的音高变化更宽容
    private const double RatioGainPerMs = 1.5e-4;

    private readonly float[] _ring = new float[RingFrames];
    private readonly string? _deviceId;
    private readonly string? _deviceName;
    private readonly ManualResetEventSlim _startupDone = new(false);

    private long _writeFrame;
    private double _readPos;
    private double _ratio = 1.0;
    private bool _primed;

    private Thread? _thread;
    private volatile bool _stopRequested;

    private long _framesReceived;
    private long _packetsReceived;
    private long _underruns;
    private long _drops;
    private double _waterMs;

    public MicUplinkPlayer(string? deviceId, string? deviceName = null)
    {
        _deviceId = deviceId;
        _deviceName = deviceName;
    }

    public string? DeviceName { get; private set; }

    public string? FatalError { get; private set; }

    public bool IsRunning => _thread is { IsAlive: true };

    public long PacketsReceived => Interlocked.Read(ref _packetsReceived);

    public long FramesReceived => Interlocked.Read(ref _framesReceived);

    public long Underruns => Interlocked.Read(ref _underruns);

    public long Drops => Interlocked.Read(ref _drops);

    /// <summary>当前水位（毫秒）。稳态应在 30–50 ms 之间。</summary>
    public double WaterMs => Volatile.Read(ref _waterMs);

    /// <summary>重采样比率（1.0 = 不补偿）。</summary>
    public double Ratio => _ratio;

    /// <summary>UDP 接收线程调用：推进手机送来的单声道帧（48 kHz）。</summary>
    public void PushMic(ReadOnlySpan<short> mono, int frames)
    {
        if (frames <= 0)
        {
            return;
        }

        Interlocked.Increment(ref _packetsReceived);
        Interlocked.Add(ref _framesReceived, frames);

        var write = Interlocked.Read(ref _writeFrame);
        // 写满就丢最旧的（读端会追上来），避免覆盖正在读的位置
        var readable = write - (long)Math.Floor(_readPos);
        if (readable + frames > RingFrames - 64)
        {
            Interlocked.Increment(ref _drops);
            return;
        }

        for (var i = 0; i < frames; i++)
        {
            _ring[(int)((write + i) % RingFrames)] = mono[i] / 32768f;
        }

        Interlocked.Exchange(ref _writeFrame, write + frames);

        // 第一个包到达时把读指针放到"目标水位之后"，否则稳态水位会一直贴着 0，
        // 每个回调都要欠载一次才能靠比率慢慢补回来。
        if (!_primed && frames > 0)
        {
            _primed = true;
            _readPos = Math.Max(0, write + frames - (InitialWaterMs * 48.0));
        }
    }

    public void Start()
    {
        if (_thread is not null)
        {
            return;
        }

        _startupDone.Reset();
        _stopRequested = false;
        _thread = new Thread(RenderThread)
        {
            IsBackground = true,
            Name = "EchoLink-MicRender",
            Priority = ThreadPriority.Highest,
        };
        _thread.Start();
        _startupDone.Wait(TimeSpan.FromSeconds(3));
    }

    public void Stop()
    {
        _stopRequested = true;
        try
        {
            _thread?.Join(TimeSpan.FromSeconds(2));
        }
        catch (ThreadStateException)
        {
        }

        _thread = null;
    }

    public void Dispose() => Stop();

    private void RenderThread()
    {
        Com.CoInitializeEx(IntPtr.Zero, Com.CoinitMultithreaded);
        using var realtime = RealtimeScope.EnterProAudio();

        IMMDeviceEnumerator? enumerator = null;
        IMMDevice? device = null;
        object? clientRaw = null;
        IAudioClient? client = null;
        IAudioRenderClient? renderClient = null;
        WaitableEvent? audioEvent = null;
        var mixFormat = IntPtr.Zero;
        float[] scratch = Array.Empty<float>();

        try
        {
            enumerator = Com.CreateDeviceEnumerator();
            device = Com.OpenRenderDevice(enumerator, _deviceId);
            DeviceName = _deviceName ?? _deviceId ?? "系统默认输出";

            clientRaw = Com.Activate(device, AudioClientFlags.IidIAudioClient);
            client = (IAudioClient)clientRaw;

            var hr = client.GetMixFormat(out mixFormat);
            if (hr != 0)
            {
                throw new WasapiException("GetMixFormat 失败", hr);
            }

            var format = DeviceFormat.FromPointer(mixFormat);
            if (format.Encoding != SampleEncoding.Float32 || format.BitsPerSample != 32)
            {
                throw new WasapiException(
                    $"上行播放目前只支持 32 位浮点混音格式，该端点是 {format}。请换一个虚拟声卡端点。",
                    unchecked((int)0x88890008));
            }

            hr = client.Initialize(AudioClientShareMode.Shared, AudioClientStreamFlags.EventCallback, 0, 0, mixFormat, IntPtr.Zero);
            if (hr != 0)
            {
                var fallbackPeriod = 2_000_000L;
                hr = client.Initialize(AudioClientShareMode.Shared, AudioClientStreamFlags.EventCallback, fallbackPeriod, 0, mixFormat, IntPtr.Zero);
                if (hr != 0)
                {
                    throw new WasapiException("Initialize 上行渲染流失败", hr);
                }
            }

            hr = client.GetBufferSize(out var bufferFrames);
            if (hr != 0)
            {
                throw new WasapiException("GetBufferSize 失败", hr);
            }

            audioEvent = new WaitableEvent("EchoLink-MicRender");
            hr = client.SetEventHandle(audioEvent.Handle);
            if (hr != 0)
            {
                throw new WasapiException("SetEventHandle 失败", hr);
            }

            var serviceId = AudioClientFlags.IidIAudioRenderClient;
            hr = client.GetService(ref serviceId, out var service);
            if (hr != 0 || service is not IAudioRenderClient render)
            {
                throw new WasapiException("GetService(IAudioRenderClient) 失败", hr);
            }

            renderClient = render;
            scratch = new float[bufferFrames * format.Channels];

            hr = client.Start();
            if (hr != 0)
            {
                throw new WasapiException("Start 失败", hr);
            }

            _startupDone.Set();

            var stepBase = (double)PacketFormat.MicSampleRate / format.SampleRate;

            while (!_stopRequested)
            {
                if (!audioEvent.Wait(100))
                {
                    continue;
                }

                if (client.GetCurrentPadding(out var padding) != 0)
                {
                    continue;
                }

                var available = (int)(bufferFrames - padding);
                if (available <= 0)
                {
                    continue;
                }

                if (renderClient.GetBuffer((uint)available, out var buffer) != 0 || buffer == IntPtr.Zero)
                {
                    continue;
                }

                Fill(buffer, available, format, stepBase);
                renderClient.ReleaseBuffer((uint)available, 0);
            }
        }
        catch (Exception ex)
        {
            FatalError = ex.Message;
            _startupDone.Set();
        }
        finally
        {
            try
            {
                client?.Stop();
            }
            catch
            {
                // 关闭阶段尽力而为
            }

            audioEvent?.Dispose();
            if (mixFormat != IntPtr.Zero)
            {
                Com.CoTaskMemFree(mixFormat);
            }

            ReleaseCom(clientRaw);
            ReleaseCom(device);
            ReleaseCom(enumerator);            Com.CoUninitialize();
            _ = scratch;
        }
    }

    /// <summary>按变速率读指针填一块输出缓冲（单声道源 → 任意声道数）。</summary>
    private unsafe void Fill(IntPtr buffer, int frames, DeviceFormat format, double stepBase)
    {
        var channels = format.Channels;
        var output = new Span<float>((void*)buffer, frames * channels);

        var write = Interlocked.Read(ref _writeFrame);
        var step = stepBase * _ratio;

        // 长时间断流后的积压：直接跳到目标水位，别让延迟永久偏大
        var waterFrames = write - (long)Math.Floor(_readPos);
        if (waterFrames > (long)(MaxWaterMs * 48))
        {
            _readPos = write - (long)(TargetWaterMs * 48.0);
            Interlocked.Increment(ref _drops);
        }

        for (var i = 0; i < frames; i++)
        {
            var index = (long)Math.Floor(_readPos);
            if (index + 1 >= write)
            {
                // 欠载：补静音且不推进读指针，等下一包
                for (var c = 0; c < channels; c++)
                {
                    output[(i * channels) + c] = 0f;
                }

                Interlocked.Increment(ref _underruns);
                continue;
            }

            var frac = (float)(_readPos - index);
            var a = _ring[(int)(index % RingFrames)];
            var b = _ring[(int)((index + 1) % RingFrames)];
            var sample = a + ((b - a) * frac);
            for (var c = 0; c < channels; c++)
            {
                output[(i * channels) + c] = sample;
            }

            _readPos += step;
        }

        // ---- 水位闭环：目标 40 ms，比率限 ±0.5% ----
        var levelMs = (write - _readPos) * 1000.0 / PacketFormat.MicSampleRate;
        Volatile.Write(ref _waterMs, levelMs);

        var errorMs = levelMs - TargetWaterMs;
        var target = 1.0 + (errorMs * RatioGainPerMs);
        target = Math.Clamp(target, 1.0 - MaxRatio, 1.0 + MaxRatio);
        _ratio += (target - _ratio) * 0.05;
        _ratio = Math.Clamp(_ratio, 1.0 - MaxRatio, 1.0 + MaxRatio);
    }

    private static void ReleaseCom(object? comObject)
    {
        if (comObject is null || !Marshal.IsComObject(comObject))
        {
            return;
        }

        try
        {
            Marshal.ReleaseComObject(comObject);
        }
        catch
        {
            // 关闭阶段尽力而为
        }
    }

    /// <summary>启动时把读指针放在"目标水位"之后，避免第一个回调就欠载。</summary>
    internal void PrimeWater()
    {
        var write = Interlocked.Read(ref _writeFrame);
        _readPos = Math.Max(0, write - (long)(InitialWaterMs * 48.0));
    }
}
