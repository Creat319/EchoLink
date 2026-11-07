using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using EchoLink.Core.Audio;

namespace EchoLink.Core.Wasapi;

public sealed class RenderEngineInfo
{
    public required string Mode { get; init; }
    public required DeviceFormat Format { get; init; }
    public bool UsedAudioClient3 { get; init; }
    public string? FallbackReason { get; init; }
    public uint RequestedPeriodFrames { get; init; }
    public uint DefaultEnginePeriodFrames { get; init; }
    public uint MinEnginePeriodFrames { get; init; }
    public uint BufferFrames { get; init; }

    public double EnginePeriodMs => RequestedPeriodFrames > 0 && Format.SampleRate > 0
        ? RequestedPeriodFrames * 1000.0 / Format.SampleRate
        : 0;

    public override string ToString()
        => $"{Mode} | 引擎周期 {EnginePeriodMs:F2}ms (default {DefaultEnginePeriodFrames}f / min {MinEnginePeriodFrames}f) | 缓冲 {BufferFrames}f";
}

/// <summary>
/// 保活渲染流：持续向“正在被环回抓取的那个端点”写静音。
/// 作用只有一个——让端点在没有任何程序出声时仍然按引擎周期投递数据（否则环回静默时完全不产数据）。
/// 顺带承担两个职责：
/// <list type="bullet">
/// <item>路径 B 的实测载体：用 IAudioClient3 请求最小引擎周期，间接拉低同一端点的环回投递粒度。</item>
/// <item>测试音/点击声发生器：写进同一条流，因此必然经过设备的混音与环回路径，
/// 能真正验证“PC 出声 → 环回 → 网络 → 手机”整条链路，而不是在代码里假装播放。</item>
/// </list>
/// </summary>
public sealed class KeepAliveRenderer : IDisposable
{
    private readonly string? _deviceId;
    private readonly bool _preferSmallPeriod;
    private readonly ConcurrentQueue<ToneEvent> _events = new();
    private readonly ManualResetEventSlim _startupDone = new(false);

    private Thread? _thread;
    private volatile bool _stopRequested;
    private long _renderedFrames;
    private float[] _toneScratch = Array.Empty<float>();
    private byte[] _byteScratch = Array.Empty<byte>();
    private byte[] _silenceScratch = Array.Empty<byte>();

    public KeepAliveRenderer(string? deviceId, bool preferSmallPeriod = true)
    {
        _deviceId = deviceId;
        _preferSmallPeriod = preferSmallPeriod;
    }

    public RenderEngineInfo? EngineInfo { get; private set; }

    public string? FatalError { get; private set; }

    public bool IsRunning => _thread is { IsAlive: true };

    public double RenderedSeconds => Interlocked.Read(ref _renderedFrames) / 48000.0;

    public void Start()
    {
        if (IsRunning)
        {
            return;
        }

        _stopRequested = false;
        FatalError = null;
        Interlocked.Exchange(ref _renderedFrames, 0);
        _startupDone.Reset();

        _thread = new Thread(RenderThread)
        {
            IsBackground = true,
            Name = "EchoLink-KeepAlive",
            Priority = ThreadPriority.AboveNormal,
        };
        _thread.Start();

        if (!_startupDone.Wait(TimeSpan.FromSeconds(6)))
        {
            throw new WasapiException("保活渲染线程启动超时", unchecked((int)0x800705B4));
        }

        if (FatalError is not null)
        {
            throw new WasapiException(FatalError, unchecked((int)0x80004005));
        }
    }

    public void Stop()
    {
        _stopRequested = true;
        _thread?.Join(TimeSpan.FromSeconds(3));
        _thread = null;
    }

    public void Dispose() => Stop();

    /// <summary>排一个正弦音（会经过设备混音 → 环回 → 手机）。</summary>
    public void EnqueueTone(double frequencyHz, int durationMs, double amplitude = 0.25)
    {
        var frames = (long)Math.Max(1, durationMs) * 48000 / 1000;
        _events.Enqueue(new ToneEvent(Interlocked.Read(ref _renderedFrames), frames, frequencyHz, Math.Clamp(amplitude, 0.0, 1.0)));
    }

    /// <summary>
    /// 排一串点击声（默认每 1000 ms 一次，每次 2 ms 的 2 kHz 短音）。
    /// 用于 docs/testing.md 里的延迟测量：第三台设备同时录 PC 喇叭和手机喇叭，量两个起点的偏移。
    /// </summary>
    public void EnqueueClickTrain(int clicks, int intervalMs = 1000, double amplitude = 0.5)
    {
        var startFrame = Interlocked.Read(ref _renderedFrames);
        var intervalFrames = (long)Math.Max(10, intervalMs) * 48000 / 1000;
        for (var i = 0; i < Math.Max(1, clicks); i++)
        {
            _events.Enqueue(new ToneEvent(startFrame + (i * intervalFrames), 96, 2000.0, Math.Clamp(amplitude, 0.0, 1.0)));
        }
    }

    private void RenderThread()
    {
        Com.CoInitializeEx(IntPtr.Zero, Com.CoinitMultithreaded);
        using var realtime = RealtimeScope.EnterProAudio();

        IMMDeviceEnumerator? enumerator = null;
        IMMDevice? device = null;
        object? clientRaw = null;
        IAudioRenderClient? renderClient = null;
        WaitableEvent? audioEvent = null;
        IAudioClient? client = null;
        var mixFormat = IntPtr.Zero;

        try
        {
            enumerator = Com.CreateDeviceEnumerator();
            device = Com.OpenRenderDevice(enumerator, _deviceId);

            bool hasClient3;
            try
            {
                clientRaw = Com.Activate(device, AudioClientFlags.IidIAudioClient3);
                hasClient3 = true;
            }
            catch (WasapiException)
            {
                clientRaw = Com.Activate(device, AudioClientFlags.IidIAudioClient);
                hasClient3 = false;
            }

            client = (IAudioClient)clientRaw;
            var client3 = clientRaw as IAudioClient3;

            var hr = client.GetMixFormat(out mixFormat);
            if (hr != 0)
            {
                throw new WasapiException("GetMixFormat 失败", hr);
            }

            var format = DeviceFormat.FromPointer(mixFormat);
            var flags = AudioClientStreamFlags.EventCallback;
            var modeDescription = "IAudioClient 共享模式渲染 (保活)";
            var requestedPeriod = 0u;
            var defaultPeriod = 0u;
            var minPeriod = 0u;
            string? fallbackReason = null;
            var usedClient3 = false;

            if (hasClient3 && client3 is not null)
            {
                var hrPeriod = client3.GetSharedModeEnginePeriod(mixFormat, out defaultPeriod, out _, out minPeriod, out _);
                if (hrPeriod != 0)
                {
                    fallbackReason = $"GetSharedModeEnginePeriod: {AudioClientFlags.FormatHResult(hrPeriod)}";
                }

                // 路径 B：用一个显式请求最小周期的渲染客户端，
                // 让同一端点的引擎按最小周期传输，从而间接拉低环回投递粒度。
                if (_preferSmallPeriod && minPeriod > 0)
                {
                    var hrSmall = client3.InitializeSharedAudioStream(flags, minPeriod, mixFormat, IntPtr.Zero);
                    if (hrSmall == 0)
                    {
                        modeDescription = "IAudioClient3 渲染小周期 (路径 B)";
                        requestedPeriod = minPeriod;
                        usedClient3 = true;
                    }
                    else
                    {
                        fallbackReason = $"InitializeSharedAudioStream(render, {minPeriod}f): {AudioClientFlags.FormatHResult(hrSmall)}";
                    }
                }
            }

            if (requestedPeriod == 0)
            {
                hr = client.Initialize(AudioClientShareMode.Shared, flags, 0, 0, mixFormat, IntPtr.Zero);
                if (hr != 0)
                {
                    var periodHns = defaultPeriod > 0 ? defaultPeriod * 10_000L : 200_000L;
                    hr = client.Initialize(AudioClientShareMode.Shared, flags, periodHns * 2, 0, mixFormat, IntPtr.Zero);
                }

                if (hr != 0)
                {
                    throw new WasapiException("Initialize 保活渲染流失败", hr);
                }

                requestedPeriod = defaultPeriod;
            }

            hr = client.GetBufferSize(out var bufferFrames);
            if (hr != 0)
            {
                throw new WasapiException("GetBufferSize 失败", hr);
            }

            audioEvent = new WaitableEvent("EchoLink-KeepAliveRender");
            hr = client.SetEventHandle(audioEvent.Handle);
            if (hr != 0)
            {
                throw new WasapiException("SetEventHandle 失败", hr);
            }

            var serviceId = AudioClientFlags.IidIAudioRenderClient;
            hr = client.GetService(ref serviceId, out var service);
            if (hr != 0 || service is not IAudioRenderClient serviceRender)
            {
                throw new WasapiException("GetService(IAudioRenderClient) 失败", hr);
            }

            renderClient = serviceRender;

            var maxBytes = (int)bufferFrames * format.BlockAlign;
            _silenceScratch = new byte[maxBytes];
            _byteScratch = new byte[maxBytes];
            _toneScratch = new float[bufferFrames * 2];

            hr = client.Start();
            if (hr != 0)
            {
                throw new WasapiException("Start 失败", hr);
            }

            EngineInfo = new RenderEngineInfo
            {
                Mode = modeDescription,
                Format = format,
                UsedAudioClient3 = usedClient3,
                FallbackReason = fallbackReason,
                RequestedPeriodFrames = requestedPeriod,
                DefaultEnginePeriodFrames = defaultPeriod,
                MinEnginePeriodFrames = minPeriod,
                BufferFrames = bufferFrames,
            };

            _startupDone.Set();

            while (!_stopRequested)
            {
                if (!audioEvent.Wait(100))
                {
                    continue;
                }

                hr = client.GetCurrentPadding(out var padding);
                if (hr != 0)
                {
                    continue;
                }

                var available = (int)bufferFrames - (int)padding;
                while (available > 0 && !_stopRequested)
                {
                    var chunk = Math.Min(available, (int)bufferFrames);
                    hr = renderClient.GetBuffer((uint)chunk, out var data);
                    if (hr != 0)
                    {
                        break;
                    }

                    var startFrame = Interlocked.Read(ref _renderedFrames);
                    WriteAudio(data, chunk, startFrame, format);

                    renderClient.ReleaseBuffer((uint)chunk, 0);
                    Interlocked.Add(ref _renderedFrames, chunk);
                    available -= chunk;
                }
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
            }

            audioEvent?.Dispose();

            if (renderClient is not null)
            {
                Marshal.ReleaseComObject(renderClient);
            }

            if (clientRaw is not null && Marshal.IsComObject(clientRaw))
            {
                Marshal.ReleaseComObject(clientRaw);
            }

            if (device is not null && Marshal.IsComObject(device))
            {
                Marshal.ReleaseComObject(device);
            }

            if (enumerator is not null && Marshal.IsComObject(enumerator))
            {
                Marshal.ReleaseComObject(enumerator);
            }

            if (mixFormat != IntPtr.Zero)
            {
                Com.CoTaskMemFree(mixFormat);
            }

            Com.CoUninitialize();
        }
    }

    private void WriteAudio(IntPtr destination, int frames, long startFrame, DeviceFormat format)
    {
        // 先丢掉已经过期的音事件
        while (_events.TryPeek(out var head) && head.StartFrame + head.Frames <= startFrame)
        {
            _events.TryDequeue(out _);
        }

        if (!_events.TryPeek(out var current) || current.StartFrame >= startFrame + frames)
        {
            Marshal.Copy(_silenceScratch, 0, destination, frames * format.BlockAlign);
            return;
        }

        if (frames * 2 > _toneScratch.Length)
        {
            _toneScratch = new float[frames * 2];
        }

        Array.Clear(_toneScratch, 0, frames * 2);
        var sampleRate = format.SampleRate;
        var fadeFrames = Math.Max(1, sampleRate / 500); // 2 ms

        for (var i = 0; i < frames; i++)
        {
            var globalFrame = startFrame + i;
            if (globalFrame < current.StartFrame || globalFrame >= current.StartFrame + current.Frames)
            {
                continue;
            }

            var local = globalFrame - current.StartFrame;
            var fadeIn = Math.Min(1.0, local / (double)fadeFrames);
            var fadeOut = Math.Min(1.0, (current.Frames - 1 - local) / (double)fadeFrames);
            var envelope = Math.Min(fadeIn, fadeOut);
            if (envelope <= 0)
            {
                continue;
            }

            var value = (float)(current.Amplitude * envelope * Math.Sin(2.0 * Math.PI * current.FrequencyHz * local / sampleRate));
            _toneScratch[i * 2] = value;
            _toneScratch[i * 2 + 1] = value;
        }

        var converted = SampleConverter.FromStereoFloat(format, _toneScratch, frames, _byteScratch);
        if (converted <= 0)
        {
            Marshal.Copy(_silenceScratch, 0, destination, frames * format.BlockAlign);
            return;
        }

        Marshal.Copy(_byteScratch, 0, destination, frames * format.BlockAlign);
    }

    private readonly record struct ToneEvent(long StartFrame, long Frames, double FrequencyHz, double Amplitude);
}
