using System.Runtime.InteropServices;
using EchoLink.Core.Audio;

namespace EchoLink.Core.Wasapi;

public enum CaptureEngineMode
{
    /// <summary>先试 IAudioClient3 小周期环回，失败自动回退普通 IAudioClient。</summary>
    Auto,

    /// <summary>路径 A：只走 IAudioClient3::InitializeSharedAudioStream + LOOPBACK 标志。</summary>
    SmallPeriodLoopback,

    /// <summary>只用普通 IAudioClient（10 ms 引擎周期兜底）。</summary>
    Legacy,
}

public interface ILoopbackSink
{
    void OnLoopbackData(ReadOnlySpan<byte> data, int frames, bool silent, ulong devicePosition);

    void OnLoopbackLog(string message);
}

public sealed class CaptureEngineInfo
{
    public required string DeviceId { get; init; }
    public required string Mode { get; init; }
    public required DeviceFormat Format { get; init; }
    public bool UsedAudioClient3 { get; init; }
    public string? FallbackReason { get; init; }
    public uint DefaultEnginePeriodFrames { get; init; }
    public uint MinEnginePeriodFrames { get; init; }
    public uint RequestedPeriodFrames { get; init; }
    public uint BufferFrames { get; init; }

    public double EnginePeriodMs => RequestedPeriodFrames > 0 && Format.SampleRate > 0
        ? RequestedPeriodFrames * 1000.0 / Format.SampleRate
        : 0;

    public double BufferMs => Format.SampleRate > 0 ? BufferFrames * 1000.0 / Format.SampleRate : 0;

    public override string ToString()
        => $"{Mode} | {Format} | 引擎周期 {EnginePeriodMs:F2}ms (default {DefaultEnginePeriodFrames}f / min {MinEnginePeriodFrames}f) | 缓冲 {BufferMs:F2}ms";
}

public sealed class CaptureCounters
{
    private long _callbacks;
    private long _frames;
    private long _silentFrames;
    private long _discontinuities;
    private long _errors;
    private int _minFramesPerCallback = int.MaxValue;
    private int _maxFramesPerCallback;
    private int _lastFramesPerCallback;

    public long Callbacks => Interlocked.Read(ref _callbacks);
    public long Frames => Interlocked.Read(ref _frames);
    public long SilentFrames => Interlocked.Read(ref _silentFrames);
    public long Discontinuities => Interlocked.Read(ref _discontinuities);
    public long Errors => Interlocked.Read(ref _errors);
    public int MinFramesPerCallback => Volatile.Read(ref _minFramesPerCallback);
    public int MaxFramesPerCallback => Volatile.Read(ref _maxFramesPerCallback);
    public int LastFramesPerCallback => Volatile.Read(ref _lastFramesPerCallback);

    internal void RecordCallback(int frames, bool silent)
    {
        Interlocked.Increment(ref _callbacks);
        Interlocked.Add(ref _frames, frames);
        if (silent)
        {
            Interlocked.Add(ref _silentFrames, frames);
        }

        if (frames < Volatile.Read(ref _minFramesPerCallback))
        {
            Volatile.Write(ref _minFramesPerCallback, frames);
        }

        if (frames > Volatile.Read(ref _maxFramesPerCallback))
        {
            Volatile.Write(ref _maxFramesPerCallback, frames);
        }

        Volatile.Write(ref _lastFramesPerCallback, frames);
    }

    internal void RecordDiscontinuity() => Interlocked.Increment(ref _discontinuities);

    internal void RecordError() => Interlocked.Increment(ref _errors);

    public void Reset()
    {
        Interlocked.Exchange(ref _callbacks, 0);
        Interlocked.Exchange(ref _frames, 0);
        Interlocked.Exchange(ref _silentFrames, 0);
        Interlocked.Exchange(ref _discontinuities, 0);
        Interlocked.Exchange(ref _errors, 0);
        Volatile.Write(ref _minFramesPerCallback, int.MaxValue);
        Volatile.Write(ref _maxFramesPerCallback, 0);
        Volatile.Write(ref _lastFramesPerCallback, 0);
    }
}

/// <summary>
/// 事件驱动 WASAPI 环回抓取。
/// 线程模型：内部一条专用线程完成 COM 初始化、开流、事件等待与取数，所有数据回调都在该线程上同步发生
/// （不排队、不分配），调用方在自己的回调里完成转换/重采样/打包。
/// </summary>
public sealed class LoopbackCapture : IDisposable
{
    private readonly string? _deviceId;
    private readonly CaptureEngineMode _mode;
    private readonly ILoopbackSink _sink;
    private readonly ManualResetEventSlim _startupDone = new(false);

    private Thread? _thread;
    private volatile bool _stopRequested;

    public LoopbackCapture(string? deviceId, CaptureEngineMode mode, ILoopbackSink sink)
    {
        _deviceId = deviceId;
        _mode = mode;
        _sink = sink;
    }

    public CaptureEngineInfo? EngineInfo { get; private set; }

    public CaptureCounters Counters { get; } = new();

    public bool IsRunning => _thread is { IsAlive: true };

    public string? FatalError { get; private set; }

    public void Start()
    {
        if (IsRunning)
        {
            return;
        }

        _stopRequested = false;
        FatalError = null;
        _startupDone.Reset();
        // 每个会话用全新的计数器，避免 UI 上读出上一轮的数字
        Counters.Reset();

        _thread = new Thread(CaptureThread)
        {
            IsBackground = true,
            Name = "EchoLink-WasapiCapture",
            Priority = ThreadPriority.AboveNormal,
        };
        _thread.Start();

        if (!_startupDone.Wait(TimeSpan.FromSeconds(6)))
        {
            throw new WasapiException("环回抓取线程启动超时", unchecked((int)0x800705B4));
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

    private void CaptureThread()
    {
        Com.CoInitializeEx(IntPtr.Zero, Com.CoinitMultithreaded);
        using var realtime = RealtimeScope.EnterProAudio();

        IMMDeviceEnumerator? enumerator = null;
        IMMDevice? device = null;
        object? clientRaw = null;
        IAudioCaptureClient? captureClient = null;
        WaitableEvent? audioEvent = null;
        var mixFormat = IntPtr.Zero;
        IAudioClient? client = null;

        try
        {
            enumerator = Com.CreateDeviceEnumerator();
            device = Com.OpenRenderDevice(enumerator, _deviceId);
            var hrDeviceId = device.GetId(out var deviceId);
            if (hrDeviceId != 0)
            {
                deviceId = _deviceId ?? "<default>";
            }

            // IAudioClient3 在 Win10 1703+ 都有；端点不支持时回退基础接口
            bool hasClient3;
            try
            {
                clientRaw = Com.Activate(device, AudioClientFlags.IidIAudioClient3);
                hasClient3 = true;
            }
            catch (WasapiException ex)
            {
                _sink.OnLoopbackLog($"IAudioClient3 不可用（{ex.Message}），使用基础 IAudioClient");
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
            if (format.Encoding == SampleEncoding.Unsupported)
            {
                throw new WasapiException($"不支持的端点格式 {format}", unchecked((int)0x88890008));
            }

            var flags = AudioClientStreamFlags.EventCallback | AudioClientStreamFlags.Loopback;
            var requestedPeriod = 0u;
            var defaultPeriod = 0u;
            var minPeriod = 0u;
            string? fallbackReason = null;
            var modeDescription = "IAudioClient (legacy shared)";
            var usedClient3 = false;

            if (hasClient3 && client3 is not null)
            {
                var hrPeriod = client3.GetSharedModeEnginePeriod(mixFormat, out defaultPeriod, out _, out minPeriod, out _);
                if (hrPeriod != 0)
                {
                    fallbackReason = $"GetSharedModeEnginePeriod: {AudioClientFlags.FormatHResult(hrPeriod)}";
                    defaultPeriod = 0;
                    minPeriod = 0;
                }
            }

            // 路径 A：环回流直接请求小周期。官方文档标注环回标志不受支持，
            // 实测若返回 0x88890021 (AUDCLNT_E_INVALID_STREAM_FLAG) 就落到下面的兜底。
            if (_mode is CaptureEngineMode.Auto or CaptureEngineMode.SmallPeriodLoopback
                && client3 is not null
                && minPeriod > 0)
            {
                var hrSmall = client3.InitializeSharedAudioStream(flags, minPeriod, mixFormat, IntPtr.Zero);
                if (hrSmall == 0)
                {
                    modeDescription = "IAudioClient3 环回小周期 (路径 A)";
                    requestedPeriod = minPeriod;
                    usedClient3 = true;
                }
                else
                {
                    fallbackReason = $"InitializeSharedAudioStream(loopback, {minPeriod}f): {AudioClientFlags.FormatHResult(hrSmall)}";
                    _sink.OnLoopbackLog($"路径 A 失败：{fallbackReason}，回退普通 IAudioClient");
                }
            }
            else if (_mode == CaptureEngineMode.SmallPeriodLoopback)
            {
                fallbackReason = "IAudioClient3 或小周期信息不可用";
            }

            if (requestedPeriod == 0)
            {
                // 兜底：普通共享模式环回流。hnsBufferDuration 给 0 让引擎决定；
                // 若平台拒绝则退到一个引擎周期的 2 倍。
                hr = client.Initialize(
                    AudioClientShareMode.Shared,
                    AudioClientStreamFlags.EventCallback | AudioClientStreamFlags.Loopback,
                    0, 0, mixFormat, IntPtr.Zero);
                if (hr != 0)
                {
                    var periodHns = defaultPeriod > 0 ? defaultPeriod * 10_000L : 200_000L;
                    hr = client.Initialize(
                        AudioClientShareMode.Shared,
                        AudioClientStreamFlags.EventCallback | AudioClientStreamFlags.Loopback,
                        periodHns * 2, 0, mixFormat, IntPtr.Zero);
                }

                if (hr != 0)
                {
                    throw new WasapiException("Initialize 环回流失败", hr);
                }

                requestedPeriod = defaultPeriod;
                modeDescription = "IAudioClient 共享模式环回 (兜底)";
            }

            hr = client.GetBufferSize(out var bufferFrames);
            if (hr != 0)
            {
                throw new WasapiException("GetBufferSize 失败", hr);
            }

            audioEvent = new WaitableEvent("EchoLink-LoopbackCapture");
            hr = client.SetEventHandle(audioEvent.Handle);
            if (hr != 0)
            {
                throw new WasapiException("SetEventHandle 失败", hr);
            }

            var serviceId = AudioClientFlags.IidIAudioCaptureClient;
            hr = client.GetService(ref serviceId, out var service);
            if (hr != 0 || service is not IAudioCaptureClient serviceCapture)
            {
                throw new WasapiException("GetService(IAudioCaptureClient) 失败", hr);
            }

            captureClient = serviceCapture;

            hr = client.Start();
            if (hr != 0)
            {
                throw new WasapiException("Start 失败", hr);
            }

            EngineInfo = new CaptureEngineInfo
            {
                DeviceId = deviceId ?? _deviceId ?? string.Empty,
                Mode = modeDescription,
                Format = format,
                UsedAudioClient3 = usedClient3,
                FallbackReason = fallbackReason,
                DefaultEnginePeriodFrames = defaultPeriod,
                MinEnginePeriodFrames = minPeriod,
                RequestedPeriodFrames = requestedPeriod,
                BufferFrames = bufferFrames,
            };

            _sink.OnLoopbackLog($"环回抓取已启动：{EngineInfo}");
            _startupDone.Set();

            PumpLoop(client, captureClient, audioEvent, format, (int)(Math.Max(bufferFrames, (uint)(format.SampleRate / 20)) * (uint)format.BlockAlign));
        }
        catch (Exception ex)
        {
            FatalError = ex.Message;
            _sink.OnLoopbackLog("环回抓取错误: " + ex);
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
                // 关闭路径尽量避免抛异常打断清理
            }

            audioEvent?.Dispose();

            if (captureClient is not null)
            {
                Marshal.ReleaseComObject(captureClient);
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

    private void PumpLoop(IAudioClient client, IAudioCaptureClient captureClient, WaitableEvent audioEvent, DeviceFormat format, int byteCapacity)
    {
        var buffer = new byte[Math.Max(byteCapacity, 4096)];

        while (!_stopRequested)
        {
            if (!audioEvent.Wait(100))
            {
                continue;
            }

            var drain = true;
            while (drain && !_stopRequested)
            {
                var hr = captureClient.GetNextPacketSize(out var packetFrames);
                if (hr != 0)
                {
                    Counters.RecordError();
                    _sink.OnLoopbackLog($"GetNextPacketSize 失败: {AudioClientFlags.FormatHResult(hr)}");
                    _stopRequested = true;
                    break;
                }

                if (packetFrames == 0)
                {
                    break;
                }

                hr = captureClient.GetBuffer(out var data, out var frames, out var flags, out var devicePosition, out _);
                if (hr != 0)
                {
                    Counters.RecordError();
                    _sink.OnLoopbackLog($"GetBuffer 失败: {AudioClientFlags.FormatHResult(hr)}");
                    if (unchecked((uint)hr) is 0x88890004 or 0x88890001)
                    {
                        // 端点失效/流被重置：交给引擎重启
                        _stopRequested = true;
                    }

                    break;
                }

                var byteCount = (int)frames * format.BlockAlign;
                if (byteCount > buffer.Length)
                {
                    buffer = new byte[byteCount];
                }

                var silent = (flags & AudioClientFlags.BufferFlagsSilent) != 0 || data == IntPtr.Zero;
                if (silent)
                {
                    Array.Clear(buffer, 0, byteCount);
                }
                else
                {
                    Marshal.Copy(data, buffer, 0, byteCount);
                }

                if ((flags & AudioClientFlags.BufferFlagsDataDiscontinuity) != 0)
                {
                    Counters.RecordDiscontinuity();
                }

                captureClient.ReleaseBuffer(frames);

                if (_stopRequested)
                {
                    break;
                }

                Counters.RecordCallback((int)frames, silent);
                _sink.OnLoopbackData(buffer.AsSpan(0, byteCount), (int)frames, silent, devicePosition);
            }
        }
    }
}
