using System.Diagnostics;
using System.Net;
using EchoLink.Core.Audio;
using EchoLink.Core.Protocol;
using EchoLink.Core.Transport;
using EchoLink.Core.Wasapi;
using NAudio.Wave;

namespace EchoLink.Core.Engine;

public enum EchoLinkLinkState
{
    Idle,
    Starting,
    Probing,
    StreamingAwaitingReport,
    Connected,
    Stopped,
    Faulted,
}

public sealed class EchoLinkSnapshot
{
    public EchoLinkLinkState LinkState { get; init; }
    public string LinkDescription { get; init; } = string.Empty;
    public string Target { get; init; } = "-";
    public string DeviceName { get; init; } = "-";
    public string CaptureFormat { get; init; } = "-";
    public string CaptureMode { get; init; } = "-";
    public string KeepAliveMode { get; init; } = "-";
    public double CaptureEnginePeriodMs { get; init; }
    public double KeepAliveEnginePeriodMs { get; init; }
    public int CaptureBufferFrames { get; init; }
    public int CaptureSampleRate { get; init; }
    public int MinFramesPerCallback { get; init; }
    public int MaxFramesPerCallback { get; init; }
    public int LastFramesPerCallback { get; init; }
    public long Callbacks { get; init; }
    public long FramesCaptured { get; init; }
    public double CapturedSeconds { get; init; }
    public long PacketsSent { get; init; }
    public double MaxSendIntervalMs { get; init; }
    public long SendStalls { get; init; }
    public double PacketsPerSecond { get; init; }
    public long SendErrors { get; init; }
    public long SilentCallbacks { get; init; }
    public long Discontinuities { get; init; }
    public double PeakLevel { get; init; }
    public double RmsLevel { get; init; }
    public double NominalLatencyMs { get; init; }
    public string? LastError { get; init; }
    public IReadOnlyList<string> Candidates { get; init; } = Array.Empty<string>();
    public PhoneReport? Phone { get; init; }
    public double SecondsSincePhoneReport { get; init; }
}

/// <summary>
/// 发送端引擎：环回抓取 → 立体声 float → 48 kHz 重采样 → 240 帧分包 → UDP。
/// 整条热路径跑在 WASAPI 抓取线程上，不排队、不加锁、不分配（缓冲一次性预分配）。
/// </summary>
public sealed class EchoLinkEngine : ILoopbackSink, IPacketSink, IDisposable
{
    private readonly EchoLinkOptions _options;
    private readonly Stopwatch _clock = new();
    private double _lastSendMs;
    private double _maxSendIntervalMs;
    private long _sendStalls;
    private readonly List<string> _log = new();
    private readonly object _logLock = new();

    private UdpAudioTransport? _transport;
    private LoopbackCapture? _capture;
    private KeepAliveRenderer? _keepAlive;
    private StreamingResampler? _resampler;
    private Packetizer? _packetizer;

    private float[] _stereoScratch = new float[8192 * 2];
    private float[] _resampledScratch = new float[8192 * 2];
    private short[] _dumpScratch = new short[8192 * 2];
    private WaveFileWriter? _waveWriter;

    private Thread? _linkThread;
    private Thread? _monitorThread;
    private volatile bool _stop;

    private long _framesCaptured;
    private long _silentCallbacks;
    private long _dspOverruns;
    private double _peakHold;
    private double _rmsInstant;
    private long _lastSentPackets;
    private double _packetsPerSecond;
    private volatile string? _lastError;

    private EchoLinkLinkState _linkState = EchoLinkLinkState.Idle;
    private string _linkDescription = "未启动";
    private PhoneReport? _phoneReport;
    private long _phoneReportTimestamp;
    private IReadOnlyList<TargetCandidate> _candidates = Array.Empty<TargetCandidate>();

    public EchoLinkEngine(EchoLinkOptions options)
    {
        _options = options;
    }

    public event Action<string>? LogEmitted;

    public event Action<PhoneReport>? PhoneReportReceived;

    public bool IsRunning => _capture is not null && _monitorThread is { IsAlive: true };

    public IReadOnlyList<TargetCandidate> Candidates => _candidates;

    public void Start()
    {
        if (IsRunning)
        {
            return;
        }

        _stop = false;
        _lastError = null;
        _clock.Restart();
        _framesCaptured = 0;
        _silentCallbacks = 0;
        _peakHold = 0;
        _linkState = EchoLinkLinkState.Starting;
        _linkDescription = "正在打开音频端点…";

        _transport = new UdpAudioTransport(_options.ReportPort);
        _transport.ReportReceived += OnReportReceived;

        // 保活流要先于环回抓取启动：路径 B 是“只要有一个应用请求小缓冲，引擎就按它传输”，
        // 所以必须先有这个客户端在，再把环回流的引擎周期带下来。
        if (_options.EnableKeepAlive)
        {
            _keepAlive = new KeepAliveRenderer(_options.CaptureDeviceId, _options.PreferSmallPeriodKeepAlive);
            try
            {
                _keepAlive.Start();
                Log($"保活流已启动：{_keepAlive.EngineInfo}");
            }
            catch (Exception ex)
            {
                Log("保活流启动失败（静音时会没有数据，但播放仍然工作）: " + ex.Message);
                _keepAlive = null;
            }
        }

        _capture = new LoopbackCapture(_options.CaptureDeviceId, _options.CaptureMode, this);
        _capture.Start();

        var info = _capture.EngineInfo ?? throw new InvalidOperationException("环回抓取没有返回引擎信息");
        _resampler = new StreamingResampler(info.Format.SampleRate);
        // 时间戳用**真实发送时刻**（QPC 微秒），不再是 sampleCount 推导出的名义时间。
        // 名义时间戳是 sampleCount 的函数，信息冗余；换成发送时刻后，接收端做差分会自然消掉
        // PC 侧的成批投递延迟，只留下链路与调度抖动，才能区分“PC 卡了”和“网络卡了”。
        _packetizer = new Packetizer(this, _ => (uint)(_clock.Elapsed.TotalMilliseconds * 1000.0));

        if (!string.IsNullOrWhiteSpace(_options.WavDumpPath))
        {
            StartWavDump(_options.WavDumpPath!);
        }

        _linkState = EchoLinkLinkState.StreamingAwaitingReport;
        _linkDescription = "已开始发送，等待手机回报…";

        _linkThread = new Thread(LinkThread) { IsBackground = true, Name = "EchoLink-Link" };
        _linkThread.Start();

        _monitorThread = new Thread(MonitorThread) { IsBackground = true, Name = "EchoLink-Monitor" };
        _monitorThread.Start();

        Log($"引擎已启动：{info}");
    }

    public void Stop()
    {
        if (_capture is null && _transport is null && _monitorThread is null)
        {
            // 幂等：Stop() 之后 Dispose() 还会再调一次，不要在日志里重复打“引擎已停止”
            return;
        }

        _stop = true;
        _linkThread?.Join(TimeSpan.FromSeconds(2));
        _monitorThread?.Join(TimeSpan.FromSeconds(2));
        _linkThread = null;
        _monitorThread = null;

        _capture?.Dispose();
        _capture = null;

        _keepAlive?.Dispose();
        _keepAlive = null;

        if (_transport is not null)
        {
            _transport.ReportReceived -= OnReportReceived;
            _transport.Dispose();
            _transport = null;
        }

        StopWavDump();

        _linkState = EchoLinkLinkState.Stopped;
        _linkDescription = "已停止";
        Log("引擎已停止");
    }

    public void PlayTestTone()
    {
        _keepAlive?.EnqueueTone(1000, 600, 0.3);
        Log("已排入 1 kHz / 600 ms 测试音（经设备混音与环回路径）");
    }

    public void PlayClickTrain(int clicks = 10, int intervalMs = 1000)
    {
        _keepAlive?.EnqueueClickTrain(clicks, intervalMs, 0.5);
        Log($"已排入 {clicks} 次点击声（间隔 {intervalMs} ms），用于第三方录音测延迟");
    }

    public void StartWavDump(string path)
    {
        StopWavDump();
        try
        {
            var directory = Path.GetDirectoryName(Path.GetFullPath(path));
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            _waveWriter = new WaveFileWriter(path, new WaveFormat(PacketFormat.SampleRate, 16, PacketFormat.Channels));
            Log($"开始写诊断 WAV：{path}");
        }
        catch (Exception ex)
        {
            Log("诊断 WAV 打开失败: " + ex.Message);
        }
    }

    public void StopWavDump()
    {
        var writer = Interlocked.Exchange(ref _waveWriter, null);
        if (writer is null)
        {
            return;
        }

        try
        {
            writer.Flush();
            writer.Dispose();
            Log("诊断 WAV 已关闭");
        }
        catch (Exception ex)
        {
            Log("诊断 WAV 关闭失败: " + ex.Message);
        }
    }

    public void Dispose()
    {
        Stop();
    }

    public EchoLinkSnapshot GetSnapshot()
    {
        var capture = _capture;
        var keepAlive = _keepAlive;
        var transport = _transport;
        var info = capture?.EngineInfo;
        var counters = capture?.Counters;
        var report = _phoneReport;
        var reportTicks = Interlocked.Read(ref _phoneReportTimestamp);

        return new EchoLinkSnapshot
        {
            LinkState = _linkState,
            LinkDescription = _linkDescription,
            Target = transport?.Remote?.ToString() ?? "-",
            DeviceName = _options.CaptureDeviceName,
            CaptureFormat = info?.Format.ToString() ?? "-",
            CaptureMode = info?.Mode ?? "-",
            KeepAliveMode = keepAlive?.EngineInfo?.Mode ?? (_options.EnableKeepAlive ? "未启动" : "已禁用"),
            CaptureEnginePeriodMs = info?.EnginePeriodMs ?? 0,
            KeepAliveEnginePeriodMs = keepAlive?.EngineInfo?.EnginePeriodMs ?? 0,
            CaptureBufferFrames = (int)(info?.BufferFrames ?? 0),
            CaptureSampleRate = info?.Format.SampleRate ?? 0,
            MinFramesPerCallback = counters?.MinFramesPerCallback is > 0 and < int.MaxValue ? counters.MinFramesPerCallback : 0,
            MaxFramesPerCallback = counters?.MaxFramesPerCallback ?? 0,
            LastFramesPerCallback = counters?.LastFramesPerCallback ?? 0,
            Callbacks = counters?.Callbacks ?? 0,
            FramesCaptured = Interlocked.Read(ref _framesCaptured),
            CapturedSeconds = Interlocked.Read(ref _framesCaptured) / (double)PacketFormat.SampleRate,
            PacketsSent = transport?.PacketsSent ?? 0,
            MaxSendIntervalMs = _maxSendIntervalMs,
            SendStalls = Interlocked.Read(ref _sendStalls),
            PacketsPerSecond = _packetsPerSecond,
            SendErrors = transport?.SendErrors ?? 0,
            SilentCallbacks = Interlocked.Read(ref _silentCallbacks),
            Discontinuities = counters?.Discontinuities ?? 0,
            PeakLevel = _peakHold,
            RmsLevel = _rmsInstant,
            NominalLatencyMs = (info?.EnginePeriodMs ?? 0) + (info is null ? 0 : 1000.0 * info.BufferFrames / info.Format.SampleRate),
            LastError = _lastError,
            Candidates = _candidates.Select(c => c.ToString()).ToList(),
            Phone = report,
            SecondsSincePhoneReport = reportTicks == 0 ? double.MaxValue : (Stopwatch.GetTimestamp() - reportTicks) / (double)Stopwatch.Frequency,
        };
    }

    // ------------------------------------------------------------------ 抓取回调（热路径）

    void ILoopbackSink.OnLoopbackData(ReadOnlySpan<byte> data, int frames, bool silent, ulong devicePosition)
    {
        try
        {
            if (silent)
            {
                Interlocked.Increment(ref _silentCallbacks);
            }

            var format = _capture?.EngineInfo?.Format;
            if (format is null || _resampler is null || _packetizer is null)
            {
                return;
            }

            EnsureScratch(frames);

            var converted = SampleConverter.ToStereoFloat(format, data, frames, _stereoScratch);
            if (converted <= 0)
            {
                Interlocked.Increment(ref _dspOverruns);
                return;
            }

            var resampled = _resampler.Process(_stereoScratch, converted, _resampledScratch);
            if (resampled <= 0)
            {
                return;
            }

            Interlocked.Add(ref _framesCaptured, resampled);

            // 电平台
            double peak = 0;
            double sumSquares = 0;
            var length = resampled * 2;
            for (var i = 0; i < length; i++)
            {
                var value = _resampledScratch[i];
                var magnitude = Math.Abs(value);
                if (magnitude > peak)
                {
                    peak = magnitude;
                }

                sumSquares += (double)value * value;
            }

            if (peak > _peakHold)
            {
                _peakHold = peak;
            }

            _rmsInstant = Math.Sqrt(sumSquares / Math.Max(1, length));

            if (_waveWriter is not null)
            {
                WriteDump(resampled);
            }

            _packetizer.Push(_resampledScratch, resampled);
            // 每次抓取回调结束就把尾巴发出去：包长跟着抓取粒度走（S/PDIF 上 128 帧 ≈ 2.67 ms），
            // 而不是固定攒满 240 帧再发——后者在 2.67 ms 粒度下会多等 0–5 ms。
            _packetizer.Flush();

            TrackSendInterval();
        }
        catch (Exception ex)
        {
            _lastError = "抓取回调异常: " + ex.Message;
            Log("抓取回调异常: " + ex);
        }
    }

    void ILoopbackSink.OnLoopbackLog(string message) => Log(message);

    /// <summary>
    /// 记录相邻两次抓取回调（≈相邻两个发包时刻）的间隔。
    /// 用途：把"停顿发生在发送之前还是之后"分开——真实的发送间隔放大说明 PC 侧卡了，
    /// 而手机端 |Δ到达 − Δ时间戳| 变大说明延迟在 sendto 之后。
    /// </summary>
    private void TrackSendInterval()
    {
        var now = _clock.Elapsed.TotalMilliseconds;
        var previous = _lastSendMs;
        _lastSendMs = now;

        if (previous <= 0)
        {
            return;
        }

        var interval = now - previous;
        if (interval > _maxSendIntervalMs)
        {
            _maxSendIntervalMs = interval;
        }

        if (interval > 50)
        {
            Interlocked.Increment(ref _sendStalls);
        }
    }

    private void EnsureScratch(int frames)
    {
        if (frames <= _stereoScratch.Length / 2)
        {
            return;
        }

        var capacity = Math.Max(frames, _stereoScratch.Length == 0 ? 8192 : _stereoScratch.Length / 2) * 2;
        _stereoScratch = new float[capacity];
        _resampledScratch = new float[capacity + 256];
        _dumpScratch = new short[capacity + 256];
    }

    private void WriteDump(int frames)
    {
        var writer = _waveWriter;
        if (writer is null || frames * 2 > _dumpScratch.Length)
        {
            return;
        }

        for (var i = 0; i < frames * 2; i++)
        {
            var value = _resampledScratch[i];
            _dumpScratch[i] = value switch
            {
                > 1f => 32767,
                < -1f => -32768,
                _ => (short)(value * 32767f),
            };
        }

        var bytes = frames * 2 * sizeof(short);
        var buffer = new byte[bytes];
        Buffer.BlockCopy(_dumpScratch, 0, buffer, 0, bytes);
        writer.Write(buffer, 0, bytes);
    }

    // ------------------------------------------------------------------ UDP 出口

    void IPacketSink.Send(ReadOnlySpan<byte> packet, uint sequence, ulong sampleCount)
        => _transport?.Send(packet);

    // ------------------------------------------------------------------ 链路与监控

    private void LinkThread()
    {
        var targets = ResolveTargets();
        if (targets.Count == 0)
        {
            _linkState = EchoLinkLinkState.StreamingAwaitingReport;
            _linkDescription = "未发现 USB 网络共享适配器，请在 PC 端填写手机 IP（手机界面会显示）";
            Log(_linkDescription);
            return;
        }

        foreach (var (target, label) in targets)
        {
            if (_stop || _transport is null)
            {
                return;
            }

            _linkState = EchoLinkLinkState.Probing;
            _linkDescription = $"正在探测 {label} ({target})";
            Log(_linkDescription);
            _transport.Remote = target;

            var deadline = Stopwatch.GetTimestamp() + (Stopwatch.Frequency * _options.ProbeTimeoutMs / 1000);
            while (Stopwatch.GetTimestamp() < deadline && !_stop)
            {
                if (SecondsSinceReport() < 1.0)
                {
                    _linkState = EchoLinkLinkState.Connected;
                    _linkDescription = $"已连通 {label} ({target})";
                    Log(_linkDescription);
                    return;
                }

                Thread.Sleep(50);
            }
        }

        // 没等到回报也不阻塞播放：继续向评分最高的候选发送，UI 会显示“等待回报”
        _linkState = EchoLinkLinkState.StreamingAwaitingReport;
        _linkDescription = $"已向 {targets[0].Target} 发送，但未收到手机回报（检查手机端是否已开始接收 / 防火墙）";
        _transport!.Remote = targets[0].Target;
        Log(_linkDescription);
    }

    private List<(IPEndPoint Target, string Label)> ResolveTargets()
    {
        var list = new List<(IPEndPoint, string)>();

        if (!string.IsNullOrWhiteSpace(_options.ManualTargetAddress))
        {
            if (IPAddress.TryParse(_options.ManualTargetAddress, out var manual))
            {
                list.Add((new IPEndPoint(manual, _options.AudioPort), "手动指定"));
                _candidates = Array.Empty<TargetCandidate>();
                return list;
            }

            Log($"手动地址无法解析：{_options.ManualTargetAddress}");
        }

        if (!_options.AutoDiscoverTarget)
        {
            return list;
        }

        _candidates = TargetDiscovery.DiscoverCandidates();
        foreach (var candidate in _candidates)
        {
            var address = candidate.Gateway ?? candidate.LocalAddress;
            if (address is null)
            {
                continue;
            }

            list.Add((new IPEndPoint(address, _options.AudioPort), candidate.AdapterName));
        }

        return list;
    }

    private double SecondsSinceReport()
    {
        var ticks = Interlocked.Read(ref _phoneReportTimestamp);
        return ticks == 0 ? double.MaxValue : (Stopwatch.GetTimestamp() - ticks) / (double)Stopwatch.Frequency;
    }

    private void MonitorThread()
    {
        var lastTick = Stopwatch.GetTimestamp();
        var restartAttempts = 0;

        while (!_stop)
        {
            Thread.Sleep(1000);

            var now = Stopwatch.GetTimestamp();
            var elapsed = (now - lastTick) / (double)Stopwatch.Frequency;
            lastTick = now;

            var sent = _transport?.PacketsSent ?? 0;
            _packetsPerSecond = elapsed > 0 ? (sent - _lastSentPackets) / elapsed : 0;
            _lastSentPackets = sent;

            // 电平台自然衰减
            _peakHold *= 0.75;
            _rmsInstant *= 0.5;

            if (_capture is { IsRunning: false } && !_stop)
            {
                _lastError = _capture.FatalError ?? "环回抓取已停止";
                if (restartAttempts < 5)
                {
                    restartAttempts++;
                    Log($"环回抓取中断（{_lastError}），第 {restartAttempts} 次尝试重开…");
                    try
                    {
                        _capture.Dispose();
                        _capture = new LoopbackCapture(_options.CaptureDeviceId, _options.CaptureMode, this);
                        _capture.Start();
                        _resampler = new StreamingResampler(_capture.EngineInfo!.Format.SampleRate);
                        Log($"环回抓取已重开：{_capture.EngineInfo}");
                    }
                    catch (Exception ex)
                    {
                        _lastError = ex.Message;
                        Log("重开环回抓取失败: " + ex.Message);
                    }
                }
                else
                {
                    _linkState = EchoLinkLinkState.Faulted;
                    _linkDescription = "环回抓取反复失败，请检查输出设备";
                }
            }
            else if (_capture is { IsRunning: true })
            {
                restartAttempts = 0;
            }

            if (_options.EnableKeepAlive && _keepAlive is { IsRunning: false } && !_stop)
            {
                try
                {
                    _keepAlive.Dispose();
                    _keepAlive = new KeepAliveRenderer(_options.CaptureDeviceId, _options.PreferSmallPeriodKeepAlive);
                    _keepAlive.Start();
                    Log($"保活流已重开：{_keepAlive.EngineInfo}");
                }
                catch (Exception ex)
                {
                    Log("重开保活流失败: " + ex.Message);
                }
            }

            if (_linkState == EchoLinkLinkState.Connected && SecondsSinceReport() > 5)
            {
                _linkState = EchoLinkLinkState.StreamingAwaitingReport;
                _linkDescription = "手机回报中断（超过 5 秒），仍在发送";
                Log(_linkDescription);
            }
        }
    }

    private void OnReportReceived(PhoneReport report, IPEndPoint source)
    {
        var first = _phoneReport is null || SecondsSinceReport() > 1.0;
        _phoneReport = report;
        Interlocked.Exchange(ref _phoneReportTimestamp, Stopwatch.GetTimestamp());

        if (_linkState is EchoLinkLinkState.Probing or EchoLinkLinkState.StreamingAwaitingReport)
        {
            _linkState = EchoLinkLinkState.Connected;
            _linkDescription = $"已连通 {source}";
            Log($"手机回报到达：来自 {source}，水位 {report.WaterMs}ms / 目标 {report.TargetWaterMs}ms");
        }
        else if (first)
        {
            Log($"收到手机回报：{source}, water={report.WaterMs}ms target={report.TargetWaterMs}ms jitterP99={report.JitterP99Us}us");
        }

        PhoneReportReceived?.Invoke(report);
    }

    private void Log(string message)
    {
        var line = $"{DateTime.Now:HH:mm:ss.fff}  {message}";
        lock (_logLock)
        {
            _log.Add(line);
            if (_log.Count > 400)
            {
                _log.RemoveRange(0, 100);
            }
        }

        LogEmitted?.Invoke(line);
    }

    public IReadOnlyList<string> GetLog()
    {
        lock (_logLock)
        {
            return _log.ToArray();
        }
    }
}

public sealed class EchoLinkOptions
{
    public string? CaptureDeviceId { get; set; }

    public string CaptureDeviceName { get; set; } = "系统默认输出";

    public CaptureEngineMode CaptureMode { get; set; } = CaptureEngineMode.Auto;

    /// <summary>是否让保活渲染流去请求最小引擎周期（路径 B）。</summary>
    public bool PreferSmallPeriodKeepAlive { get; set; } = true;

    public bool EnableKeepAlive { get; set; } = true;

    public int ReportPort { get; set; } = PacketFormat.DefaultReportPort;

    public int AudioPort { get; set; } = PacketFormat.DefaultAudioPort;

    public bool AutoDiscoverTarget { get; set; } = true;

    public string? ManualTargetAddress { get; set; }

    public int ProbeTimeoutMs { get; set; } = 1200;

    public string? WavDumpPath { get; set; }
}
