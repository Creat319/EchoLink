using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using EchoLink.Core.Audio;
using EchoLink.Core.Engine;
using EchoLink.Core.Protocol;
using EchoLink.Core.Transport;
using EchoLink.Core.Wasapi;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace EchoLink.Diagnostics;

/// <summary>
/// 无界面诊断工具：在真机上单独验证链路各环节，不依赖安卓端。
/// </summary>
internal static class Program
{
    private static int Main(string[] args)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var command = args.Length > 0 ? args[0].ToLowerInvariant() : "help";
        var options = ParseOptions(args.Skip(1).ToArray());

        try
        {
            return command switch
            {
                "devices" => ListDevices(),
                "periods" => ListPeriods(),
                "targets" => ListTargets(),
                "capture" => CaptureAndDump(options),
                "echo" => EchoSelfTest(options),
                "sink" => RunSink(options),
                "send" => SendSynthetic(options),
                _ => PrintHelp(),
            };
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("失败: " + ex);
            return 2;
        }
    }

    private static int PrintHelp()
    {
        Console.WriteLine("""
            EchoLink 诊断工具

              devices                          列出所有活动渲染端点（含默认端点标记）
              periods                          查询每个端点能否拿到 <10 ms 的共享引擎周期（只读探测）
              targets                          USB 网络共享候选地址（取网关，不写死 IP）
              capture [--device 关键字] [--seconds 5] [--out dump.wav] [--mode auto|legacy|a]
                                               环回抓取 → 重采样 48k → 写 WAV，并打印引擎周期/回调统计
                                               （会自动通过保活流播一个 1kHz 测试音，所以 WAV 里应当有实际波形）
              echo [--seconds 8] [--out dump.wav] [--device 关键字] [--quiet true]
                                               完整 PC 链路自检：引擎 → 127.0.0.1 回环 UDP → 本机 sink
                                               校验包长/序号/累计采样数连续性，并向引擎回发状态回报
                                               （--seconds 300 就是一次浸泡测试）
              sink [--port 47810] [--seconds 10] [--reply true]
                                               只做 UDP 收包校验（可给手机端做对照）
              send [--to 127.0.0.1] [--port 47810] [--seconds 5] [--freq 1000]
                                               只发合成音频包（1kHz），用于单独验证安卓端接收
            """);
        return 0;
    }

    private static Dictionary<string, string> ParseOptions(string[] args)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < args.Length; i++)
        {
            var key = args[i];
            if (!key.StartsWith("--", StringComparison.Ordinal))
            {
                continue;
            }

            var value = i + 1 < args.Length && !args[i + 1].StartsWith("--", StringComparison.Ordinal)
                ? args[++i]
                : "true";
            result[key[2..]] = value;
        }

        return result;
    }

    private static string Option(Dictionary<string, string> options, string key, string fallback)
        => options.TryGetValue(key, out var value) ? value : fallback;

    private static int OptionInt(Dictionary<string, string> options, string key, int fallback)
        => options.TryGetValue(key, out var value) && int.TryParse(value, out var parsed) ? parsed : fallback;

    private static int ListDevices()
    {
        using var enumerator = new MMDeviceEnumerator();
        string? defaultId = null;
        try
        {
            defaultId = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).ID;
        }
        catch (Exception ex)
        {
            Console.WriteLine("读取默认端点失败: " + ex.Message);
        }

        Console.WriteLine("活动渲染端点：");
        foreach (var device in enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            var isDefault = device.ID == defaultId ? " [默认]" : string.Empty;
            Console.WriteLine($"  {device.FriendlyName}{isDefault}");

            try
            {
                using var client = device.AudioClient;
                var mix = client.MixFormat;
                Console.WriteLine($"    {mix.SampleRate}Hz {mix.Channels}ch {mix.BitsPerSample}bit {mix.Encoding}");
                Console.WriteLine($"    engine default period = {TimeSpan.FromTicks(client.DefaultDevicePeriod).TotalMilliseconds:F2} ms");
            }
            catch (Exception ex)
            {
                Console.WriteLine("    读取混音格式失败: " + ex.Message);
            }
        }

        return 0;
    }

    private static int ListPeriods()
    {
        using var enumerator = new MMDeviceEnumerator();
        var results = new List<EndpointPeriods>();

        foreach (var device in enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            string id;
            string name;
            try
            {
                id = device.ID;
                name = device.FriendlyName;
            }
            catch (Exception ex)
            {
                Console.WriteLine("  跳过无法读取的端点: " + ex.Message);
                continue;
            }

            var probe = EnginePeriodProbe.Probe(id, name);
            results.Add(probe);
            Console.WriteLine("  " + probe);
        }

        Console.WriteLine();
        var small = results.Where(r => r.SupportsSmallPeriod).ToList();
        if (small.Count == 0)
        {
            Console.WriteLine("结论: 所有端点都不支持 <10 ms 的共享引擎周期（本机只能吃 10 ms 兜底）。");
            return 1;
        }

        Console.WriteLine("结论: 以下端点可尝试 IAudioClient3 小周期路径：");
        foreach (var p in small)
        {
            Console.WriteLine($"  {p.FriendlyName}  min {p.MinMs:F2} ms");
        }

        return 0;
    }

    private static int ListTargets()    {
        var candidates = TargetDiscovery.DiscoverCandidates();
        if (candidates.Count == 0)
        {
            Console.WriteLine("没有发现候选适配器（手机 USB 网络共享是否已打开？）");
            return 1;
        }

        Console.WriteLine("候选目标（按评分排序，分数越高越可能是手机）：");
        foreach (var candidate in candidates)
        {
            Console.WriteLine($"  评分 {candidate.Score,3}  {candidate.AdapterName}");
            Console.WriteLine($"      描述 {candidate.Description}");
            Console.WriteLine($"      本机 {candidate.LocalAddress}  该网段网关 {candidate.Gateway?.ToString() ?? "-"}");
            Console.WriteLine($"      理由 {candidate.Reason}");
        }

        return 0;
    }

    private static int CaptureAndDump(Dictionary<string, string> options)
    {
        var seconds = OptionInt(options, "seconds", 5);
        var outPath = Option(options, "out", Path.Combine(Environment.CurrentDirectory, "echo-capture.wav"));
        var modeName = Option(options, "mode", "auto").ToLowerInvariant();
        var mode = modeName switch
        {
            "legacy" => CaptureEngineMode.Legacy,
            "a" => CaptureEngineMode.SmallPeriodLoopback,
            _ => CaptureEngineMode.Auto,
        };

        var deviceId = ResolveDeviceId(Option(options, "device", string.Empty));
        Console.WriteLine($"抓取端点: {deviceId ?? "<系统默认>"}, 时长 {seconds}s, 模式 {mode}");

        using var keepAlive = new KeepAliveRenderer(deviceId, preferSmallPeriod: true);
        keepAlive.Start();
        Console.WriteLine($"保活流/路径B: {keepAlive.EngineInfo}");

        LoopbackCapture? capture = null;
        var sink = new CaptureSink(outPath, () => capture?.EngineInfo?.Format);
        capture = new LoopbackCapture(deviceId, mode, sink);

        using var captureScope = capture;
        capture.Start();
        Console.WriteLine($"环回抓取: {capture.EngineInfo}");

        Thread.Sleep(150);
        keepAlive.EnqueueTone(1000, 400, 0.3);

        var clock = Stopwatch.StartNew();
        var nextReport = 1.0;
        while (clock.Elapsed.TotalSeconds < seconds)
        {
            Thread.Sleep(100);
            if (clock.Elapsed.TotalSeconds >= nextReport)
            {
                nextReport += 1;
                var counters = capture.Counters;
                Console.WriteLine(
                    $"  t={clock.Elapsed.TotalSeconds:F0}s 回调 {counters.Callbacks} 帧 {counters.Frames} " +
                    $"每回调 {counters.MinFramesPerCallback}~{counters.MaxFramesPerCallback} 帧 峰值 {sink.Peak:F3}");
            }
        }

        capture.Stop();
        keepAlive.Stop();

        sink.Close();
        Console.WriteLine($"写入 WAV: {outPath}（{sink.FramesWritten} 帧 @48k）");
        Console.WriteLine(sink.Describe());

        var ok = sink.Peak > 0.01 && capture.Counters.Callbacks > 10;
        Console.WriteLine(ok ? "结果: OK" : "结果: FAIL（没抓到非零音频或回调太少）");
        return ok ? 0 : 1;
    }

    private static int EchoSelfTest(Dictionary<string, string> options)
    {
        var seconds = OptionInt(options, "seconds", 8);
        var outPath = Option(options, "out", string.Empty);
        var deviceId = ResolveDeviceId(Option(options, "device", string.Empty));
        var quiet = Option(options, "quiet", "false") == "true";

        var receiver = new LoopbackSink(PacketFormat.DefaultAudioPort, replyReports: true);
        receiver.Start();

        var engineOptions = new EchoLinkOptions
        {
            CaptureDeviceId = deviceId,
            CaptureDeviceName = "诊断自检",
            AutoDiscoverTarget = false,
            ManualTargetAddress = "127.0.0.1",
            AudioPort = PacketFormat.DefaultAudioPort,
            ReportPort = PacketFormat.DefaultReportPort,
            EnableKeepAlive = true,
            PreferSmallPeriodKeepAlive = true,
            ProbeTimeoutMs = 800,
            WavDumpPath = string.IsNullOrWhiteSpace(outPath) ? null : outPath,
        };

        using var engine = new EchoLinkEngine(engineOptions);
        engine.LogEmitted += line =>
        {
            if (!quiet)
            {
                Console.WriteLine("  [pc] " + line);
            }
        };

        engine.Start();
        Thread.Sleep(500);
        engine.PlayTestTone();

        var stopwatch = Stopwatch.StartNew();
        var nextReport = 0.5;
        while (stopwatch.Elapsed.TotalSeconds < seconds)
        {
            Thread.Sleep(200);
            if (stopwatch.Elapsed.TotalSeconds < nextReport)
            {
                continue;
            }

            nextReport += 0.5;
            var snapshot = engine.GetSnapshot();
            Console.WriteLine(
                $"  t={stopwatch.Elapsed.TotalSeconds:F1}s 状态={snapshot.LinkState} 包={snapshot.PacketsSent} " +
                $"包/秒={snapshot.PacketsPerSecond:F1} 峰值={snapshot.PeakLevel:F3} " +
                $"手机回报={(snapshot.Phone is { } phone ? $"{phone.WaterMs}ms/{phone.TargetWaterMs}ms" : "无")}");
        }

        engine.Stop();
        receiver.Stop();

        Console.WriteLine(receiver.Describe());
        var ok = receiver.Validate();
        Console.WriteLine(ok ? "结果: OK" : "结果: FAIL");
        return ok ? 0 : 1;
    }

    private static int RunSink(Dictionary<string, string> options)
    {
        var port = OptionInt(options, "port", PacketFormat.DefaultAudioPort);
        var seconds = OptionInt(options, "seconds", 10);

        var sink = new LoopbackSink(port, Option(options, "reply", "false") == "true");
        sink.Start();
        Console.WriteLine($"监听 UDP {port}，{seconds}s…");
        Thread.Sleep(seconds * 1000);
        sink.Stop();
        Console.WriteLine(sink.Describe());
        var ok = sink.Validate();
        Console.WriteLine(ok ? "结果: OK" : "结果: FAIL");
        return ok ? 0 : 1;
    }

    private static int SendSynthetic(Dictionary<string, string> options)
    {
        var host = Option(options, "to", "127.0.0.1");
        var port = OptionInt(options, "port", PacketFormat.DefaultAudioPort);
        var seconds = OptionInt(options, "seconds", 5);
        var frequency = OptionInt(options, "freq", 1000);

        using var client = new UdpClient();
        var remote = new IPEndPoint(IPAddress.Parse(host), port);
        var buffer = new byte[PacketFormat.PacketBytes];
        var payload = MemoryMarshal.Cast<byte, short>(
            buffer.AsSpan(PacketFormat.PayloadOffset, PacketFormat.PayloadBytes));

        var sw = Stopwatch.StartNew();
        uint sequence = 0;
        ulong sampleCount = 0;
        var nextSend = 0.0;

        while (sw.Elapsed.TotalSeconds < seconds)
        {
            while (sw.Elapsed.TotalSeconds < nextSend)
            {
                Thread.Sleep(1);
            }

            for (var frame = 0; frame < PacketFormat.FramesPerPacket; frame++)
            {
                var t = (sampleCount + (ulong)frame) / 48000.0;
                var value = (short)(Math.Sin(2 * Math.PI * frequency * t) * 8000);
                payload[frame * 2] = value;
                payload[frame * 2 + 1] = value;
            }

            BinaryPrimitives.WriteUInt32LittleEndian(buffer, sequence);
            BinaryPrimitives.WriteUInt64LittleEndian(buffer.AsSpan(4), sampleCount);
            BinaryPrimitives.WriteUInt32LittleEndian(buffer.AsSpan(12), (uint)(sw.ElapsedMilliseconds * 1000));

            client.Send(buffer, buffer.Length, remote);

            sequence++;
            sampleCount += PacketFormat.FramesPerPacket;
            nextSend += 0.005;
        }

        Console.WriteLine($"已发送 {sequence} 个包（{sampleCount} 帧）到 {remote}");
        return 0;
    }

    private static string? ResolveDeviceId(string hint)
    {
        if (string.IsNullOrWhiteSpace(hint))
        {
            return null;
        }

        using var enumerator = new MMDeviceEnumerator();
        foreach (var device in enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            if (device.FriendlyName.Contains(hint, StringComparison.OrdinalIgnoreCase))
            {
                Console.WriteLine($"匹配端点: {device.FriendlyName}");
                return device.ID;
            }
        }

        Console.WriteLine($"没有端点匹配 '{hint}'，改用系统默认端点");
        return null;
    }
}

/// <summary>环回数据 → 48k/16bit/立体声 WAV，并统计峰值。</summary>
internal sealed class CaptureSink : ILoopbackSink
{
    private readonly WaveFileWriter _writer;
    private readonly Func<DeviceFormat?> _formatProvider;
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    private float[] _stereo = new float[48000];
    private float[] _resampled = new float[48000];
    private short[] _pcm = new short[48000];
    private byte[] _bytes = new byte[96000];
    private StreamingResampler? _resampler;
    private DeviceFormat? _format;
    private long _callbacks;
    private double _peak;

    public CaptureSink(string path, Func<DeviceFormat?> formatProvider)
    {
        _writer = new WaveFileWriter(path, new WaveFormat(PacketFormat.SampleRate, 16, PacketFormat.Channels));
        _formatProvider = formatProvider;
    }

    public long FramesWritten { get; private set; }

    public double Peak => _peak;

    public void OnLoopbackData(ReadOnlySpan<byte> data, int frames, bool silent, ulong devicePosition)
    {
        _callbacks++;
        _format ??= _formatProvider();
        if (_format is null || _format.Encoding == SampleEncoding.Unsupported)
        {
            return;
        }

        _resampler ??= new StreamingResampler(_format.SampleRate);

        if (frames * 2 > _stereo.Length)
        {
            _stereo = new float[frames * 2];
            _resampled = new float[frames * 2 + 64];
            _pcm = new short[frames * 2 + 64];
            _bytes = new byte[(frames * 2 + 64) * 2];
        }

        var converted = SampleConverter.ToStereoFloat(_format, data, frames, _stereo);
        var produced = _resampler.Process(_stereo, converted, _resampled);
        if (produced <= 0)
        {
            return;
        }

        for (var i = 0; i < produced * 2; i++)
        {
            var value = _resampled[i];
            var magnitude = Math.Abs(value);
            if (magnitude > _peak)
            {
                _peak = magnitude;
            }

            _pcm[i] = (short)Math.Clamp(value * 32767f, short.MinValue, short.MaxValue);
        }

        var byteCount = produced * 4;
        Buffer.BlockCopy(_pcm, 0, _bytes, 0, byteCount);
        _writer.Write(_bytes, 0, byteCount);
        FramesWritten += produced;
    }

    public void OnLoopbackLog(string message) => Console.WriteLine("  " + message);

    public string Describe()
    {
        var elapsed = _clock.Elapsed.TotalSeconds;
        return $"设备格式 {_format}; 回调 {_callbacks} 次；48k 帧 {FramesWritten}（{FramesWritten / 48000.0:F2}s）；" +
               $"实际经过 {elapsed:F2}s；峰值 {_peak:F3}";
    }

    public void Close()
    {
        _writer.Flush();
        _writer.Dispose();
    }
}

/// <summary>
/// UDP 收包 + 线格式校验，可选回发状态回报包。
///
/// 注意：这是**测量工具**，必须比被测对象快得多，否则会把工具自身的抖动算到链路头上。
/// 所以这里：接收缓冲 4 MB、到达间隔用定长直方图（不排序、列表不增长）、回报按 100 ms 节流、
/// 每个包只做 O(1) 统计。
/// </summary>
internal sealed class LoopbackSink
{
    private const int BucketPerMillisecond = 4;   // 0.25 ms 分桶
    private const int BucketCount = 1024;         // 覆盖 256 ms
    private const double ReportIntervalMs = 100;  // 回报节流

    private readonly int _port;
    private readonly bool _replyReports;
    private readonly long[] _intervalBuckets = new long[BucketCount];
    private UdpClient? _client;
    private Thread? _thread;
    private volatile bool _stop;

    private uint _lastSequence;
    private ulong _expectedSampleCount;
    private bool _first = true;
    private double _lastArrivalMs;
    private double _maxIntervalMs;
    private long _intervalCount;
    private long _peakAbs;

    private double _lastReportMs;
    private int _cachedP99Us;

    // 抖动差分对比：真实发送时刻 vs 旧的名义时间戳
    private readonly long[] _realJitterBuckets = new long[BucketCount];
    private readonly long[] _nominalJitterBuckets = new long[BucketCount];
    private long _realJitterCount;
    private long _nominalJitterCount;
    private double _realJitterMaxMs;
    private double _nominalJitterMaxMs;
    private uint _lastTimestampUs;
    private ulong _lastNominalUs;
    private bool _hasTimestamp;

    private static void RecordJitter(long[] buckets, ref long count, ref double maxMs, double valueMs)
    {
        if (valueMs > maxMs)
        {
            maxMs = valueMs;
        }

        var bucket = (int)(valueMs * BucketPerMillisecond);
        if (bucket >= BucketCount)
        {
            bucket = BucketCount - 1;
        }

        if (bucket < 0)
        {
            bucket = 0;
        }

        buckets[bucket]++;
        count++;
    }

    private static double PercentileOf(long[] buckets, long count, double percentile)
    {
        if (count == 0)
        {
            return 0.0;
        }

        var target = (long)(count * percentile);
        long cumulative = 0;
        for (var i = 0; i < buckets.Length; i++)
        {
            cumulative += buckets[i];
            if (cumulative >= target)
            {
                return (i + 1) / (double)BucketPerMillisecond;
            }
        }

        return buckets.Length / (double)BucketPerMillisecond;
    }

    public LoopbackSink(int port, bool replyReports)
    {
        _port = port;
        _replyReports = replyReports;
    }

    public long Packets { get; private set; }

    public long Bytes { get; private set; }

    public long Malformed { get; private set; }

    public long SequenceGaps { get; private set; }

    public long SampleCountGaps { get; private set; }

    public long NonZeroSamples { get; private set; }

    public long ReportsSent { get; private set; }

    /// <summary>第一个包的 16 字节头（十六进制），用来做两端实现的互操作向量。</summary>
    public string FirstHeaderHex { get; private set; } = "-";

    /// <summary>第一个包载荷的前 8 字节（4 个采样）。</summary>
    public string FirstPayloadHex { get; private set; } = "-";

    public void Start()
    {
        _client = new UdpClient(new IPEndPoint(IPAddress.Any, _port));
        // 4 MB ≈ 20 秒的包量，避免测量工具自己溢出导致“假的丢包”
        _client.Client.ReceiveBufferSize = 4 << 20;

        _thread = new Thread(Loop) { IsBackground = true, Name = "EchoLink-DiagSink" };
        _thread.Start();
    }

    public void Stop()
    {
        _stop = true;
        _thread?.Join(TimeSpan.FromSeconds(2));
        _client?.Dispose();
        _client = null;
    }

    private void Loop()
    {
        var client = _client!;
        var clock = Stopwatch.StartNew();

        while (!_stop)
        {
            IPEndPoint source = new(IPAddress.Any, 0);
            byte[] data;
            try
            {
                data = client.Receive(ref source);
            }
            catch (SocketException)
            {
                continue;
            }
            catch (ObjectDisposedException)
            {
                return;
            }

            var arrivalMs = clock.Elapsed.TotalMilliseconds;
            double intervalMs = 0;
            if (_lastArrivalMs > 0)
            {
                var interval = arrivalMs - _lastArrivalMs;
                intervalMs = interval;
                if (interval > _maxIntervalMs)
                {
                    _maxIntervalMs = interval;
                }

                var bucket = (int)(interval * BucketPerMillisecond);
                if (bucket >= BucketCount)
                {
                    bucket = BucketCount - 1;
                }

                if (bucket < 0)
                {
                    bucket = 0;
                }

                _intervalBuckets[bucket]++;
                _intervalCount++;
            }

            _lastArrivalMs = arrivalMs;
            Packets++;
            Bytes += data.Length;

            if (!AudioPacketHeader.TryRead(data, out var header))
            {
                Malformed++;
                continue;
            }

            // 包长可变（跟随抓取粒度）：帧数从数据报长度推导，不能假定 240
            var payloadBytes = data.Length - PacketFormat.PayloadOffset;
            if (payloadBytes <= 0 || payloadBytes % PacketFormat.BytesPerFrame != 0)
            {
                Malformed++;
                continue;
            }

            var framesInPacket = payloadBytes / PacketFormat.BytesPerFrame;
            if (framesInPacket > PacketFormat.FramesPerPacket)
            {
                Malformed++;
                continue;
            }

            var pcm = MemoryMarshal.Cast<byte, short>(data.AsSpan(PacketFormat.PayloadOffset, payloadBytes));
            var peak = _peakAbs;
            long nonZero = 0;
            foreach (var sample in pcm)
            {
                var magnitude = sample == short.MinValue ? 32768 : Math.Abs((int)sample);
                if (magnitude > 0)
                {
                    nonZero++;
                    if (magnitude > peak)
                    {
                        peak = magnitude;
                    }
                }
            }

            _peakAbs = peak;
            NonZeroSamples += nonZero;

            if (_first)
            {
                _first = false;
                // 留一份「真实 PC 发出的字节」作为安卓端解析器的互操作测试向量
                FirstHeaderHex = Convert.ToHexString(data, 0, PacketFormat.HeaderBytes);
                FirstPayloadHex = Convert.ToHexString(data, PacketFormat.PayloadOffset, 8);
            }
            else
            {
                if (header.Sequence != _lastSequence + 1)
                {
                    SequenceGaps++;
                }

                if (header.SampleCount != _expectedSampleCount)
                {
                    SampleCountGaps++;
                }
            }

            _lastSequence = header.Sequence;
            _expectedSampleCount = header.SampleCount + (ulong)framesInPacket;

            // ---- 抖动差分 d_i = Δ到达 − Δ时间戳（手机端用的就是这个量）----
            // 这里同时算两套：真实发送时刻（现在的实现）和名义时间戳（sampleCount 推导，旧实现），
            // 一次运行就能对比两者把多少 PC 侧抖动带进了控制量。
            var timestampUs = header.TimestampMicros;
            var nominalUs = header.SampleCount * 1_000_000UL / (ulong)PacketFormat.SampleRate;

            if (_hasTimestamp)
            {
                var deltaArrivalMs = intervalMs;
                var deltaRealUs = unchecked((int)(timestampUs - _lastTimestampUs));
                var deltaNominalUs = unchecked((long)(nominalUs - _lastNominalUs));

                RecordJitter(_realJitterBuckets, ref _realJitterCount, ref _realJitterMaxMs,
                    Math.Abs(deltaArrivalMs - (deltaRealUs / 1000.0)));
                RecordJitter(_nominalJitterBuckets, ref _nominalJitterCount, ref _nominalJitterMaxMs,
                    Math.Abs(deltaArrivalMs - (deltaNominalUs / 1000.0)));
            }

            _lastTimestampUs = timestampUs;
            _lastNominalUs = nominalUs;
            _hasTimestamp = true;

            if (_replyReports && arrivalMs - _lastReportMs >= ReportIntervalMs)
            {
                _lastReportMs = arrivalMs;
                SendReport(source);
            }
        }
    }

    private void SendReport(IPEndPoint destination)
    {
        _cachedP99Us = (int)Math.Min(ushort.MaxValue, PercentileMillis(0.99) * 1000.0);

        var buffer = new byte[PhoneReport.Size];
        BinaryPrimitives.WriteUInt32LittleEndian(buffer, PhoneReport.Magic);
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(4), PhoneReport.CurrentVersion);
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(6), PhoneReport.TypeStatus);
        BinaryPrimitives.WriteUInt32LittleEndian(buffer.AsSpan(8), (uint)ReportsSent);
        BinaryPrimitives.WriteUInt64LittleEndian(buffer.AsSpan(12), (ulong)Packets * PacketFormat.FramesPerPacket);
        BinaryPrimitives.WriteUInt32LittleEndian(buffer.AsSpan(20), (uint)Packets);
        BinaryPrimitives.WriteUInt32LittleEndian(buffer.AsSpan(24), (uint)SequenceGaps);
        BinaryPrimitives.WriteUInt32LittleEndian(buffer.AsSpan(28), 0);
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(32), 20);
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(34), 30);
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(36), (ushort)_cachedP99Us);
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(38), 20);

        try
        {
            _client!.Send(buffer, buffer.Length, destination);
            ReportsSent++;
        }
        catch (SocketException)
        {
        }
    }

    /// <summary>从直方图取分位数：O(桶数)，不排序、不分配。</summary>
    private double PercentileMillis(double percentile)
    {
        if (_intervalCount == 0)
        {
            return 0;
        }

        var target = (long)(_intervalCount * percentile);
        if (target < 1)
        {
            target = 1;
        }

        long cumulative = 0;
        for (var bucket = 0; bucket < BucketCount; bucket++)
        {
            cumulative += _intervalBuckets[bucket];
            if (cumulative >= target)
            {
                return (bucket + 1) / (double)BucketPerMillisecond;
            }
        }

        return _maxIntervalMs;
    }

    public bool Validate()
        => Packets > 0 && Malformed == 0 && SequenceGaps == 0 && SampleCountGaps == 0;

    public string Describe()
    {
        var duration = _lastArrivalMs / 1000.0;
        return $"""
            收包统计：包 {Packets}，字节 {Bytes}，畸形 {Malformed}，
              序号跳变 {SequenceGaps}，累计采样数跳变 {SampleCountGaps}，
              非零采样 {NonZeroSamples}，峰值 {_peakAbs / 32768.0:F3}，回报已发 {ReportsSent}，
              到达间隔 P50 {PercentileMillis(0.50):F2}ms / P90 {PercentileMillis(0.90):F2}ms / P99 {PercentileMillis(0.99):F2}ms / 最大 {_maxIntervalMs:F2}ms，
              实际速率 {(duration <= 0 ? 0 : Packets / duration):F1} 包/秒
            抖动差分 |d_i| = |Δ到达 − Δ时间戳|（手机端控制量）：
              真实发送时刻  P50 {PercentileOf(_realJitterBuckets, _realJitterCount, 0.50):F2}ms / P90 {PercentileOf(_realJitterBuckets, _realJitterCount, 0.90):F2}ms / P99 {PercentileOf(_realJitterBuckets, _realJitterCount, 0.99):F2}ms / 最大 {_realJitterMaxMs:F2}ms，
              旧名义时间戳  P50 {PercentileOf(_nominalJitterBuckets, _nominalJitterCount, 0.50):F2}ms / P90 {PercentileOf(_nominalJitterBuckets, _nominalJitterCount, 0.90):F2}ms / P99 {PercentileOf(_nominalJitterBuckets, _nominalJitterCount, 0.99):F2}ms / 最大 {_nominalJitterMaxMs:F2}ms，
            首个包头（互操作向量）: {FirstHeaderHex}
            首个包载荷前 8 字节    : {FirstPayloadHex}
            """;
    }
}
