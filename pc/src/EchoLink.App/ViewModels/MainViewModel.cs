using System.Collections.ObjectModel;
using System.IO;
using System.Reflection;
using System.Text;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using EchoLink.Core.Engine;
using EchoLink.Core.Protocol;
using EchoLink.Core.Transport;
using EchoLink.Core.Update;
using EchoLink.Core.Wasapi;
using EchoLink.App.Models;
using NAudio.CoreAudioApi;

namespace EchoLink.App.ViewModels;

/// <summary>
/// 主窗口 ViewModel：把手里的 <see cref="EchoLinkEngine"/> 状态映射成一组可绑定属性。
///
/// 线程约定：
///  - 引擎的 LogEmitted 在 WASAPI/链路/监控线程上触发 → 这里只入队，靠 Dispatcher 回流到 UI；
///  - 快照由 250 ms 的 DispatcherTimer 在 UI 线程拉取，不做反向推送，天然无竞态。
/// </summary>
public sealed class MainViewModel : ObservableObject, IDisposable
{
    private static readonly Brush ConnectedBrush = Frozen(0x7E, 0xE2, 0xB8);
    private static readonly Brush PendingBrush = Frozen(0xFF, 0xC4, 0x6B);
    private static readonly Brush ErrorBrush = Frozen(0xFF, 0x7A, 0x7A);
    private static readonly Brush NeutralBrush = Frozen(0xA8, 0xB6, 0xC6);
    private static readonly Brush ValueBrush = Frozen(0xE4, 0xEB, 0xF2);

    private const int MaxLogLines = 500;

    private readonly Dispatcher _dispatcher;
    private readonly DispatcherTimer _timer;
    private readonly AppSettings _settings;
    private readonly object _logLock = new();
    private readonly Queue<string> _pendingLog = new();
    private readonly List<string> _logLines = new();
    private int _flushPosted;

    private EchoLinkEngine? _engine;

    // ---------------- 配置项 ----------------
    private DeviceItem? _selectedDevice;
    private int _modeIndex;
    private bool _preferSmallPeriod = true;
    private bool _enableKeepAlive = true;
    private bool _autoDiscover = true;
    private string _manualTarget = string.Empty;
    private int _audioPort = PacketFormat.DefaultAudioPort;
    private int _reportPort = PacketFormat.DefaultReportPort;
    private bool _wavDumpEnabled;
    private string _wavDumpPath = string.Empty;

    // ---------------- 上行麦克风 ----------------
    private bool _enableMic;
    private DeviceItem? _selectedMicDevice;
    private string _micStatusText = "未启用";
    private string _micWaterText = "-";

    // ---------------- 运行时状态 ----------------
    private bool _isRunning;
    private string _linkStateText = "未启动";
    private Brush _linkStateBrush = NeutralBrush;
    private string _linkDescription = "选择输出设备后点「启动」";
    private string _targetText = "-";
    private string _deviceFormatText = "-";
    private string _captureModeText = "-";
    private string _keepAliveModeText = "-";
    private string _enginePeriodText = "-";
    private string _enginePeriodHint = "决定抓取延迟下限";
    private string _bufferText = "-";
    private string _framesPerCallbackText = "-";
    private string _packetsSentText = "0";
    private string _packetsPerSecondText = "0.0";
    private string _capturedText = "0.0 s";
    private string _peakText = "0.000";
    private string _rmsText = "0.000";
    private double _peakLevel;
    private string _silentCallbacksText = "0";
    private string _discontinuitiesText = "0";
    private string _sendErrorsText = "0";
    private string _captureDelayText = "-";
    private string _endToEndText = "-";
    private string _endToEndBreakdown = "等待手机回报";
    private string _wavDumpStatusText = "未写入";
    private bool _hasPhoneReport;
    private string _phoneWaterText = "-";
    private string _phoneTargetText = "-";
    private string _phoneJitterText = "-";
    private string _phoneUnderrunsText = "-";
    private string _phoneLostText = "-";
    private string _phoneLatencyText = "-";
    private string _phoneAgeText = "-";
    private string _candidatesText = "（未扫描）";
    private string _logText = string.Empty;
    private bool _showCandidates;

    public MainViewModel()
    {
        _dispatcher = Application.Current?.Dispatcher ?? Dispatcher.CurrentDispatcher;
        _settings = AppSettings.Load();

        Devices = new ObservableCollection<DeviceItem>();
        Candidates = new ObservableCollection<string>();

        StartCommand = new RelayCommand(Start, () => !IsRunning);
        StopCommand = new RelayCommand(Stop, () => IsRunning);
        TestToneCommand = new RelayCommand(PlayTestTone);
        ClickTrainCommand = new RelayCommand(PlayClickTrain);
        RefreshDevicesCommand = new RelayCommand(RefreshDevices);
        RescanCandidatesCommand = new RelayCommand(RescanCandidates);
        BrowseWavCommand = new RelayCommand(BrowseWav);
        ToggleCandidatesCommand = new RelayCommand(() => ShowCandidates = !ShowCandidates);
        CheckUpdateCommand = new RelayCommand(() => _ = CheckUpdatesAsync());
        OpenDownloadCommand = new RelayCommand(OpenDownloadPage);

        ApplySettingsToState();
        RefreshDevices();
        RescanCandidates();

        _timer = new DispatcherTimer(DispatcherPriority.Background) { Interval = TimeSpan.FromMilliseconds(250) };
        _timer.Tick += (_, _) => RefreshSnapshot();
        _timer.Start();

        // 启动后静默查一次版本；失败不打扰（离线是常态）
        _ = CheckUpdatesAsync(quiet: true);
    }

    public RelayCommand CheckUpdateCommand { get; }

    public RelayCommand OpenDownloadCommand { get; }

    /// <summary>
    /// 版本自检。**只提示 + 打开下载页**，不自动下载、不自动运行：
    /// 更新包是 zip/exe，自动执行等于把更新通道变成远程代码执行入口。
    /// </summary>
    private async Task CheckUpdatesAsync(bool quiet = false)
    {
        if (!quiet)
        {
            UpdateStatusText = "正在检查更新…";
        }

        var info = await UpdateChecker.CheckAsync();
        if (info is null)
        {
            if (!quiet)
            {
                UpdateStatusText = "检查失败（离线或服务不可用）";
            }

            return;
        }

        if (UpdateChecker.IsNewer(info.Version, LocalVersion))
        {
            HasUpdate = true;
            UpdateLinkText = $"有新版本 {info.Version} · 下载";
            AppendLog($"[更新] 发现新版本 {info.Version}（当前 {LocalVersion}）：{UpdateChecker.WindowsDownloadUrl}");
        }
        else
        {
            HasUpdate = false;
            UpdateStatusText = $"已是最新 v{LocalVersion}";
        }
    }

    private void OpenDownloadPage()
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(UpdateChecker.WindowsDownloadUrl)
            {
                UseShellExecute = true,
            });
            AppendLog("[更新] 已在浏览器打开下载页：" + UpdateChecker.WindowsDownloadUrl);
        }
        catch (Exception ex)
        {
            AppendLog("[更新] 打开下载页失败：" + ex.Message);
        }
    }

    // ==================================================================== 集合与命令
    public ObservableCollection<DeviceItem> Devices { get; }

    public ObservableCollection<string> Candidates { get; }

    public RelayCommand StartCommand { get; }

    public RelayCommand StopCommand { get; }

    public RelayCommand TestToneCommand { get; }

    public RelayCommand ClickTrainCommand { get; }

    public RelayCommand RefreshDevicesCommand { get; }

    public RelayCommand RescanCandidatesCommand { get; }

    public RelayCommand BrowseWavCommand { get; }

    public RelayCommand ToggleCandidatesCommand { get; }

    // ==================================================================== 版本自检

    /// <summary>"已是最新 0.3.1" / "正在检查更新…" / "检查失败（离线）"。</summary>
    public string UpdateStatusText
    {
        get => _updateStatusText;
        private set => Set(ref _updateStatusText, value);
    }

    /// <summary>"有新版本 0.4.0 · 下载"。</summary>
    public string UpdateLinkText
    {
        get => _updateLinkText;
        private set => Set(ref _updateLinkText, value);
    }

    public bool HasUpdate
    {
        get => _hasUpdate;
        private set
        {
            if (Set(ref _hasUpdate, value))
            {
                OnPropertyChanged(nameof(HasNoUpdate));
            }
        }
    }

    /// <summary>没有新版本时显示检查结果；有新版本时换成下载链接，两者互斥。</summary>
    public bool HasNoUpdate => !_hasUpdate;

    // ==================================================================== 配置属性
    public DeviceItem? SelectedDevice
    {
        get => _selectedDevice;
        set => Set(ref _selectedDevice, value);
    }

    // ---- 上行麦克风：把手机麦克风渲染到这个端点（通常是虚拟声卡的 CABLE Input）----
    public bool EnableMic
    {
        get => _enableMic;
        set => Set(ref _enableMic, value);
    }

    public DeviceItem? SelectedMicDevice
    {
        get => _selectedMicDevice;
        set => Set(ref _selectedMicDevice, value);
    }

    public string MicStatusText
    {
        get => _micStatusText;
        private set => Set(ref _micStatusText, value);
    }

    public string MicWaterText
    {
        get => _micWaterText;
        private set => Set(ref _micWaterText, value);
    }

    public int ModeIndex
    {
        get => _modeIndex;
        set => Set(ref _modeIndex, value);
    }

    public bool PreferSmallPeriod
    {
        get => _preferSmallPeriod;
        set => Set(ref _preferSmallPeriod, value);
    }

    public bool EnableKeepAlive
    {
        get => _enableKeepAlive;
        set => Set(ref _enableKeepAlive, value);
    }

    /// <summary>「自动发现」单选按钮。</summary>
    public bool AutoDiscover
    {
        get => _autoDiscover;
        set
        {
            if (Set(ref _autoDiscover, value))
            {
                OnPropertyChanged(nameof(ManualMode));
            }
        }
    }

    /// <summary>「手动指定」单选按钮（与 <see cref="AutoDiscover"/> 互斥）。</summary>
    public bool ManualMode
    {
        get => !_autoDiscover;
        set
        {
            if (value && Set(ref _autoDiscover, false, nameof(AutoDiscover)))
            {
                OnPropertyChanged();
            }
        }
    }

    public string ManualTarget
    {
        get => _manualTarget;
        set => Set(ref _manualTarget, value);
    }

    public int AudioPort
    {
        get => _audioPort;
        set => Set(ref _audioPort, value);
    }

    public int ReportPort
    {
        get => _reportPort;
        set => Set(ref _reportPort, value);
    }

    public bool WavDumpEnabled
    {
        get => _wavDumpEnabled;
        set
        {
            if (!Set(ref _wavDumpEnabled, value))
            {
                return;
            }

            // 运行中切换时立刻生效，不用重启引擎
            var engine = _engine;
            if (engine is { IsRunning: true })
            {
                if (value && !string.IsNullOrWhiteSpace(_wavDumpPath))
                {
                    engine.StartWavDump(_wavDumpPath);
                }
                else if (!value)
                {
                    engine.StopWavDump();
                }
            }
        }
    }

    public string WavDumpPath
    {
        get => _wavDumpPath;
        set => Set(ref _wavDumpPath, value);
    }

    // ==================================================================== 运行时属性
    public bool IsRunning
    {
        get => _isRunning;
        private set
        {
            if (!Set(ref _isRunning, value))
            {
                return;
            }

            StartCommand.RaiseCanExecuteChanged();
            StopCommand.RaiseCanExecuteChanged();
            TestToneCommand.RaiseCanExecuteChanged();
            ClickTrainCommand.RaiseCanExecuteChanged();
        }
    }

    public bool IsIdle => !_isRunning;

    public string LinkStateText
    {
        get => _linkStateText;
        private set => Set(ref _linkStateText, value);
    }

    public Brush LinkStateBrush
    {
        get => _linkStateBrush;
        private set => Set(ref _linkStateBrush, value);
    }

    public string LinkDescription
    {
        get => _linkDescription;
        private set => Set(ref _linkDescription, value);
    }

    public string TargetText
    {
        get => _targetText;
        private set => Set(ref _targetText, value);
    }

    public string DeviceFormatText
    {
        get => _deviceFormatText;
        private set => Set(ref _deviceFormatText, value);
    }

    public string CaptureModeText
    {
        get => _captureModeText;
        private set => Set(ref _captureModeText, value);
    }

    public string KeepAliveModeText
    {
        get => _keepAliveModeText;
        private set => Set(ref _keepAliveModeText, value);
    }

    public string EnginePeriodText
    {
        get => _enginePeriodText;
        private set => Set(ref _enginePeriodText, value);
    }

    /// <summary>引擎周期这一行的提示文字：端点能给多大粒度直接影响可达延迟。</summary>
    public string EnginePeriodHint
    {
        get => _enginePeriodHint;
        private set => Set(ref _enginePeriodHint, value);
    }

    public string BufferText
    {
        get => _bufferText;
        private set => Set(ref _bufferText, value);
    }

    public string FramesPerCallbackText
    {
        get => _framesPerCallbackText;
        private set => Set(ref _framesPerCallbackText, value);
    }

    public string PacketsSentText
    {
        get => _packetsSentText;
        private set => Set(ref _packetsSentText, value);
    }

    public string PacketsPerSecondText
    {
        get => _packetsPerSecondText;
        private set => Set(ref _packetsPerSecondText, value);
    }

    public string CapturedText
    {
        get => _capturedText;
        private set => Set(ref _capturedText, value);
    }

    public string PeakText
    {
        get => _peakText;
        private set => Set(ref _peakText, value);
    }

    public string RmsText
    {
        get => _rmsText;
        private set => Set(ref _rmsText, value);
    }

    public double PeakLevel
    {
        get => _peakLevel;
        private set => Set(ref _peakLevel, value);
    }

    public string SilentCallbacksText
    {
        get => _silentCallbacksText;
        private set => Set(ref _silentCallbacksText, value);
    }

    public string DiscontinuitiesText
    {
        get => _discontinuitiesText;
        private set => Set(ref _discontinuitiesText, value);
    }

    public string SendErrorsText
    {
        get => _sendErrorsText;
        private set => Set(ref _sendErrorsText, value);
    }

    // ---- 版本自检 ----
    private string _updateStatusText = string.Empty;
    private string _updateLinkText = string.Empty;
    private bool _hasUpdate;

    /// <summary>版本号（来自 Directory.Build.props 的 &lt;Version&gt;），显示在标题右侧便于排查版本不符。</summary>
    public string AppVersionText { get; } = "v" + MainViewModel.LocalVersion;

    /// <summary>纯版本号（无 v 前缀），用于和接口返回值比较。</summary>
    public static string LocalVersion { get; } =
        Assembly.GetExecutingAssembly()
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion.Split('+')[0]
        ?? Assembly.GetExecutingAssembly().GetName().Version?.ToString(3)
        ?? "0.0.0";

    /// <summary>PC 侧抓取延迟（半个抓取周期）。</summary>
    public string CaptureDelayText
    {
        get => _captureDelayText;
        private set => Set(ref _captureDelayText, value);
    }

    /// <summary>端到端平均延迟估算值。</summary>
    public string EndToEndText
    {
        get => _endToEndText;
        private set => Set(ref _endToEndText, value);
    }

    /// <summary>估算值的三段拆解，避免数字被当成实测值。</summary>
    public string EndToEndBreakdown
    {
        get => _endToEndBreakdown;
        private set => Set(ref _endToEndBreakdown, value);
    }

    public string WavDumpStatusText
    {
        get => _wavDumpStatusText;
        private set => Set(ref _wavDumpStatusText, value);
    }

    public bool HasPhoneReport
    {
        get => _hasPhoneReport;
        private set => Set(ref _hasPhoneReport, value);
    }

    public string PhoneWaterText
    {
        get => _phoneWaterText;
        private set => Set(ref _phoneWaterText, value);
    }

    public string PhoneTargetText
    {
        get => _phoneTargetText;
        private set => Set(ref _phoneTargetText, value);
    }

    public string PhoneJitterText
    {
        get => _phoneJitterText;
        private set => Set(ref _phoneJitterText, value);
    }

    public string PhoneUnderrunsText
    {
        get => _phoneUnderrunsText;
        private set => Set(ref _phoneUnderrunsText, value);
    }

    public string PhoneLostText
    {
        get => _phoneLostText;
        private set => Set(ref _phoneLostText, value);
    }

    public string PhoneLatencyText
    {
        get => _phoneLatencyText;
        private set => Set(ref _phoneLatencyText, value);
    }

    public string PhoneAgeText
    {
        get => _phoneAgeText;
        private set => Set(ref _phoneAgeText, value);
    }

    public string CandidatesText
    {
        get => _candidatesText;
        private set => Set(ref _candidatesText, value);
    }

    /// <summary>候选地址面板是否展开（默认收起，让首屏不用滚动）。</summary>
    public bool ShowCandidates
    {
        get => _showCandidates;
        private set => Set(ref _showCandidates, value);
    }

    public string LogText
    {
        get => _logText;
        private set => Set(ref _logText, value);
    }

    // ==================================================================== 行为
    private void Start()
    {
        if (_engine is not null)
        {
            return;
        }

        var options = BuildOptions();
        var engine = new EchoLinkEngine(options);
        engine.LogEmitted += OnEngineLog;

        try
        {
            engine.Start();
        }
        catch (Exception ex)
        {
            // 半启动的引擎必须丢掉，否则下次点启动会处于奇怪状态
            engine.LogEmitted -= OnEngineLog;
            engine.Dispose();

            AppendLog("启动失败：" + ex.Message);
            MessageBox.Show(
                "启动失败：\n\n" + ex.Message,
                "EchoLink",
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            return;
        }

        _engine = engine;
        IsRunning = true;
        OnPropertyChanged(nameof(IsIdle));
        LinkStateText = "启动中";
        LinkStateBrush = PendingBrush;

        WavDumpStatusText = !string.IsNullOrWhiteSpace(options.WavDumpPath) ? "写入中" : "未写入";
        SaveSettings();
        RefreshSnapshot();
    }

    private void Stop()
    {
        var engine = _engine;
        if (engine is null)
        {
            return;
        }

        _engine = null;
        engine.LogEmitted -= OnEngineLog;
        try
        {
            engine.Stop();
            engine.Dispose();
        }
        catch (Exception ex)
        {
            AppendLog("停止时出错：" + ex.Message);
        }

        IsRunning = false;
        OnPropertyChanged(nameof(IsIdle));
        LinkStateText = "已停止";
        LinkStateBrush = NeutralBrush;
        LinkDescription = "已停止";
        ResetRuntimeMetrics();
        SaveSettings();
    }

    /// <summary>停止后把上一次会话的实时数字清干净，避免顶部还挂着 200.0 包/秒这种残留。</summary>
    private void ResetRuntimeMetrics()
    {
        TargetText = "-";
        DeviceFormatText = "-";
        CaptureModeText = "-";
        KeepAliveModeText = "-";
        EnginePeriodText = "-";
        EnginePeriodHint = "决定抓取延迟下限";
        BufferText = "-";
        FramesPerCallbackText = "-";
        PacketsSentText = "0";
        PacketsPerSecondText = "0.0";
        CapturedText = "0.0 s";
        PeakText = "0.000";
        RmsText = "0.000";
        PeakLevel = 0;
        SilentCallbacksText = "0";
        DiscontinuitiesText = "0";
        SendErrorsText = "0";
        CaptureDelayText = "-";
        EndToEndText = "-";
        EndToEndBreakdown = "等待手机回报";
        HasPhoneReport = false;
        PhoneWaterText = "-";
        PhoneTargetText = "-";
        PhoneJitterText = "-";
        PhoneUnderrunsText = "-";
        PhoneLostText = "-";
        PhoneLatencyText = "-";
        PhoneAgeText = "-";
        WavDumpStatusText = "未写入";
    }

    private void PlayTestTone()
    {
        var engine = _engine;
        if (engine is null)
        {
            return;
        }

        if (!EnableKeepAlive)
        {
            AppendLog("测试音是通过保活渲染流发出的，请先勾选「启用保活流」。");
            return;
        }

        engine.PlayTestTone();
    }

    private void PlayClickTrain()
    {
        var engine = _engine;
        if (engine is null)
        {
            return;
        }

        if (!EnableKeepAlive)
        {
            AppendLog("点击声是通过保活渲染流发出的，请先勾选「启用保活流」。");
            return;
        }

        engine.PlayClickTrain(10, 1000);
        AppendLog("已排入 10 次点击声（间隔 1 s），可用于三设备录音测端到端延迟。");
    }

    private void RefreshDevices()
    {
        var previousId = _selectedDevice?.Id ?? _settings.DeviceId;

        Devices.Clear();
        var defaultItem = new DeviceItem("系统默认输出", null);
        Devices.Add(defaultItem);

        string? defaultId = null;
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            try
            {
                defaultId = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).ID;
            }
            catch (Exception ex)
            {
                AppendLog("读取默认输出端点失败（不影响枚举）：" + ex.Message);
            }

            foreach (var device in enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
            {
                try
                {
                    var isDefault = device.ID == defaultId;
                    var isVirtual = LooksVirtual(device.FriendlyName);
                    var name = device.FriendlyName + (isDefault ? "  · 默认" : string.Empty) + (isVirtual ? "  · 虚拟" : string.Empty);
                    // 只读探测该端点的最小引擎周期：它决定抓取粒度，进而决定低延迟上限。
                    // 板载声卡常见 2.67 ms；虚拟声卡与 HDMI 通常只有 10 ms。
                    var probe = EnginePeriodProbe.Probe(device.ID, device.FriendlyName);
                    Devices.Add(new DeviceItem(name, device.ID, isDefault, isVirtual, probe.MinMs, probe.DefaultMs));
                }
                catch (Exception ex)
                {
                    AppendLog("跳过无法读取的端点：" + ex.Message);
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog("枚举音频端点失败：" + ex.Message);
        }

        SelectedDevice = Devices.FirstOrDefault(d => d.Id == previousId) ?? defaultItem;

        // 上行默认挑虚拟声卡的渲染侧（VB-Cable 的 CABLE Input 之类）。
        // 名字里带 cable / virtual / 入梦 的都算候选。
        if (_selectedMicDevice is null || !Devices.Contains(_selectedMicDevice))
        {
            _selectedMicDevice = Devices
                .Where(d => d.Id is not null)
                .FirstOrDefault(d => d.IsVirtual || LooksMicSink(d.Name))
                ?? Devices.FirstOrDefault(d => d.Id is not null);
            OnPropertyChanged(nameof(SelectedMicDevice));
        }
    }

    private static bool LooksMicSink(string name)
    {
        var text = name.ToLowerInvariant();
        return text.Contains("cable") || text.Contains("virtual") || text.Contains("mic") || name.Contains("入梦");
    }

    private static bool LooksVirtual(string friendlyName)
    {
        var text = friendlyName.ToLowerInvariant();
        return text.Contains("cable")
            || text.Contains("virtual")
            || text.Contains("vb-audio")
            || text.Contains("voicemeeter")
            || text.Contains("audi orelay")
            || text.Contains("audiorelay")
            || text.Contains("loopback");
    }

    private void RescanCandidates()
    {
        try
        {
            var candidates = TargetDiscovery.DiscoverCandidates();
            Candidates.Clear();

            if (candidates.Count == 0)
            {
                CandidatesText = "未发现网络适配器候选，请改用「手动指定 IP」。";
                return;
            }

            var builder = new StringBuilder();
            foreach (var candidate in candidates)
            {
                var line = candidate.ToString();
                Candidates.Add(line);
                builder.AppendLine(line);
            }

            CandidatesText = builder.ToString().TrimEnd();
        }
        catch (Exception ex)
        {
            CandidatesText = "扫描失败：" + ex.Message;
        }
    }

    private void BrowseWav()
    {
        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            Title = "选择诊断 WAV 保存位置",
            Filter = "WAV 音频 (*.wav)|*.wav|所有文件 (*.*)|*.*",
            FileName = $"echolink-{DateTime.Now:yyyyMMdd-HHmmss}.wav",
            InitialDirectory = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
        };

        if (dialog.ShowDialog() == true)
        {
            WavDumpPath = dialog.FileName;
            WavDumpEnabled = true;
        }
    }

    private EchoLinkOptions BuildOptions() => new()
    {
        CaptureDeviceId = _selectedDevice?.Id,
        CaptureDeviceName = _selectedDevice?.Name ?? "系统默认输出",
        CaptureMode = _modeIndex switch
        {
            1 => CaptureEngineMode.SmallPeriodLoopback,
            2 => CaptureEngineMode.Legacy,
            _ => CaptureEngineMode.Auto,
        },
        PreferSmallPeriodKeepAlive = _preferSmallPeriod,
        EnableKeepAlive = _enableKeepAlive,
        AudioPort = _audioPort,
        ReportPort = _reportPort,
        AutoDiscoverTarget = _autoDiscover,
        ManualTargetAddress = string.IsNullOrWhiteSpace(_manualTarget) ? null : _manualTarget.Trim(),
        ProbeTimeoutMs = 1200,
        WavDumpPath = _wavDumpEnabled && !string.IsNullOrWhiteSpace(_wavDumpPath) ? _wavDumpPath : null,
        EnableMic = _enableMic,
        MicDeviceId = _selectedMicDevice?.Id,
        MicDeviceName = _selectedMicDevice?.Name ?? "（未选择虚拟麦克风）",
    };

    // ==================================================================== 状态刷新
    private void RefreshSnapshot()
    {
        var engine = _engine;
        if (engine is null)
        {
            return;
        }

        EchoLinkSnapshot snapshot;
        try
        {
            snapshot = engine.GetSnapshot();
        }
        catch (Exception ex)
        {
            AppendLog("读取状态失败：" + ex.Message);
            return;
        }

        LinkStateText = snapshot.LinkState switch
        {
            EchoLinkLinkState.Connected => "已连通",
            EchoLinkLinkState.Probing => "探测中",
            EchoLinkLinkState.StreamingAwaitingReport => "发送中",
            EchoLinkLinkState.Starting => "启动中",
            EchoLinkLinkState.Faulted => "故障",
            EchoLinkLinkState.Stopped => "已停止",
            _ => "未启动",
        };

        LinkStateBrush = snapshot.LinkState switch
        {
            EchoLinkLinkState.Connected => ConnectedBrush,
            EchoLinkLinkState.Probing or EchoLinkLinkState.StreamingAwaitingReport => PendingBrush,
            EchoLinkLinkState.Faulted => ErrorBrush,
            _ => NeutralBrush,
        };

        LinkDescription = snapshot.LastError is { Length: > 0 } error
            ? snapshot.LinkDescription + " ｜ " + error
            : snapshot.LinkDescription;

        TargetText = snapshot.Target;
        DeviceFormatText = snapshot.CaptureFormat;
        CaptureModeText = snapshot.CaptureMode;
        KeepAliveModeText = snapshot.KeepAliveMode;
        // 显示**实际观测到的**投递粒度，而不是我们请求的周期：
        // 板载声卡上请求 10 ms 兜底、实际却按 2.67 ms 投递（路径 B 把引擎周期拉低了），
        // 只报请求值会严重误导调参。
        var observedPeriodMs = snapshot.MaxFramesPerCallback > 0 && snapshot.CaptureSampleRate > 0
            ? snapshot.MaxFramesPerCallback * 1000.0 / snapshot.CaptureSampleRate
            : 0;
        EnginePeriodText = observedPeriodMs > 0
            ? $"{observedPeriodMs:F2} ms"
            : snapshot.CaptureEnginePeriodMs > 0
                ? $"{snapshot.CaptureEnginePeriodMs:F2} ms"
                : "-";

        EnginePeriodHint = observedPeriodMs > 0 && observedPeriodMs <= 3.0
            ? "板载小周期"
            : observedPeriodMs > 9.0
                ? "该端点仅 10 ms；换板载声卡可更低"
                : "决定抓取延迟下限";
        BufferText = snapshot.CaptureBufferFrames > 0
            ? $"{snapshot.CaptureBufferFrames} 帧"
            : "-";
        FramesPerCallbackText = snapshot.MaxFramesPerCallback > 0
            ? $"{snapshot.MinFramesPerCallback} ~ {snapshot.MaxFramesPerCallback} 帧"
            : "-";

        PacketsSentText = snapshot.PacketsSent.ToString("N0");
        PacketsPerSecondText = snapshot.PacketsPerSecond.ToString("F1");
        CapturedText = $"{snapshot.CapturedSeconds:F1} s";
        PeakText = snapshot.PeakLevel.ToString("F3");
        RmsText = snapshot.RmsLevel.ToString("F3");
        PeakLevel = Math.Clamp(snapshot.PeakLevel, 0, 1);
        SilentCallbacksText = snapshot.SilentCallbacks.ToString("N0");
        DiscontinuitiesText = snapshot.Discontinuities.ToString("N0");
        SendErrorsText = snapshot.SendErrors.ToString("N0");
        // PC 侧抓取延迟：环回按引擎周期成批投递，一帧数据平均多等半个周期。
        // 不能把"抓取缓冲"算进来——我们每次回调都把可用数据读空，那 22 ms 缓冲并不构成延迟，
        // 旧公式把 EnginePeriod + BufferFrames 相加，在 S/PDIF 上直接虚报了近 30 ms。
        var captureDelayMs = observedPeriodMs > 0 ? observedPeriodMs * 0.5 : 0;
        CaptureDelayText = captureDelayMs > 0 ? $"{captureDelayMs:F2} ms" : "-";

        if (_wavDumpEnabled && snapshot.PacketsSent > 0)
        {
            WavDumpStatusText = "写入中";
        }

        var phone = snapshot.Phone;
        HasPhoneReport = phone is not null;

        // ---- 上行麦克风 ----
        if (!snapshot.MicEnabled)
        {
            MicStatusText = _enableMic ? "未启动（引擎未运行）" : "未启用";
            MicWaterText = "-";
        }
        else if (snapshot.MicError is { Length: > 0 } micError)
        {
            MicStatusText = "失败：" + micError;
            MicWaterText = "-";
        }
        else
        {
            MicStatusText = snapshot.MicPackets > 0 ? "收包中" : "已打开，等待手机上行";
            MicWaterText = snapshot.MicPackets > 0
                ? $"{snapshot.MicWaterMs:F0} ms / {snapshot.MicPackets} 包"
                : "-";
        }
        if (phone is { } report)
        {
            PhoneWaterText = $"{report.WaterMs} ms";
            PhoneTargetText = $"{report.TargetWaterMs} ms";
            PhoneJitterText = $"{report.JitterP99Us} µs";
            PhoneUnderrunsText = report.Underruns.ToString("N0");
            PhoneLostText = report.PacketsLost.ToString("N0");
            PhoneLatencyText = $"{report.LatencyMs} ms";
            PhoneAgeText = snapshot.SecondsSincePhoneReport > 600
                ? "-"
                : $"{snapshot.SecondsSincePhoneReport:F1} s";

            // 端到端平均 ≈ PC 抓取（半周期） + 环水位（算法延迟） + 手机输出延迟（Oboe/HAL）。
            // 不含单向网络延迟：两端无时钟同步，量不出来；USB 上约 1 ms，量级上可忽略。
            var e2e = captureDelayMs + report.WaterMs + report.LatencyMs;
            EndToEndText = e2e > 0 ? $"{e2e:F0} ms" : "-";
            EndToEndBreakdown = captureDelayMs > 0
                ? $"抓取 {captureDelayMs:F1} + 水位 {report.WaterMs} + 输出 {report.LatencyMs}"
                : $"水位 {report.WaterMs} + 输出 {report.LatencyMs}";
        }
        else
        {
            PhoneWaterText = "-";
            PhoneTargetText = "-";
            PhoneJitterText = "-";
            PhoneUnderrunsText = "-";
            PhoneLostText = "-";
            PhoneLatencyText = "-";
            PhoneAgeText = "-";
            EndToEndText = "-";
            EndToEndBreakdown = "等待手机回报";
        }
    }

    // ==================================================================== 日志
    private void OnEngineLog(string line)
    {
        lock (_logLock)
        {
            _pendingLog.Enqueue(line);
        }

        // 合并刷屏：一批日志只投递一次 UI 调用
        if (Interlocked.Exchange(ref _flushPosted, 1) == 0)
        {
            _dispatcher.BeginInvoke(new Action(FlushLog), DispatcherPriority.Background);
        }
    }

    private void AppendLog(string message) => OnEngineLog($"{DateTime.Now:HH:mm:ss.fff}  {message}");

    private void FlushLog()
    {
        Interlocked.Exchange(ref _flushPosted, 0);

        List<string> batch;
        lock (_logLock)
        {
            if (_pendingLog.Count == 0)
            {
                return;
            }

            batch = new List<string>(_pendingLog);
            _pendingLog.Clear();
        }

        _logLines.AddRange(batch);
        if (_logLines.Count > MaxLogLines)
        {
            _logLines.RemoveRange(0, _logLines.Count - MaxLogLines);
        }

        LogText = string.Join(Environment.NewLine, _logLines);
        LogUpdated?.Invoke();
    }

    /// <summary>日志更新事件，供视图把滚动条拉到底。</summary>
    public event Action? LogUpdated;

    /// <summary>清空日志面板（只影响显示，不影响引擎）。</summary>
    public void ClearLog()
    {
        lock (_logLock)
        {
            _pendingLog.Clear();
        }

        _logLines.Clear();
        LogText = string.Empty;
    }

    // ==================================================================== 设置持久化
    private void ApplySettingsToState()
    {
        _modeIndex = _settings.ModeIndex;
        _preferSmallPeriod = _settings.PreferSmallPeriodKeepAlive;
        _enableKeepAlive = _settings.EnableKeepAlive;
        _autoDiscover = _settings.AutoDiscoverTarget;
        _manualTarget = _settings.ManualTargetAddress;
        _audioPort = _settings.AudioPort;
        _reportPort = _settings.ReportPort;
        _wavDumpEnabled = _settings.WavDumpEnabled;
        _wavDumpPath = _settings.WavDumpPath;
        _enableMic = _settings.EnableMic;

        SelectedDevice = null;
        OnPropertyChanged(nameof(ModeIndex));
        OnPropertyChanged(nameof(PreferSmallPeriod));
        OnPropertyChanged(nameof(EnableKeepAlive));
        OnPropertyChanged(nameof(AutoDiscover));
        OnPropertyChanged(nameof(ManualMode));
        OnPropertyChanged(nameof(ManualTarget));
        OnPropertyChanged(nameof(AudioPort));
        OnPropertyChanged(nameof(ReportPort));
        OnPropertyChanged(nameof(WavDumpEnabled));
        OnPropertyChanged(nameof(WavDumpPath));
    }

    private void SaveSettings()
    {
        _settings.DeviceId = _selectedDevice?.Id;
        _settings.DeviceName = _selectedDevice?.Name ?? "系统默认输出";
        _settings.ModeIndex = _modeIndex;
        _settings.PreferSmallPeriodKeepAlive = _preferSmallPeriod;
        _settings.EnableKeepAlive = _enableKeepAlive;
        _settings.AutoDiscoverTarget = _autoDiscover;
        _settings.ManualTargetAddress = _manualTarget;
        _settings.AudioPort = _audioPort;
        _settings.ReportPort = _reportPort;
        _settings.WavDumpEnabled = _wavDumpEnabled;
        _settings.WavDumpPath = _wavDumpPath;
        _settings.EnableMic = _enableMic;
        _settings.MicDeviceId = _selectedMicDevice?.Id;
        _settings.MicDeviceName = _selectedMicDevice?.Name ?? "（未选择虚拟麦克风）";
        _settings.Save();
    }

    public void Shutdown()
    {
        _timer.Stop();
        Stop();
        SaveSettings();
    }

    public void Dispose() => Shutdown();

    private static Brush Frozen(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
    }
}
