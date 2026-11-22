using System.IO;
using System.Windows;

namespace EchoLink.App;

public partial class App : Application
{
    private static readonly string StartupLogPath =
        Path.Combine(Path.GetTempPath(), "echolink-startup.log");

    private TrayIcon? _tray;

    /// <summary>
    /// 点关闭时是收进托盘还是真退出。由设置界面写入，关闭窗口时读。
    /// 走静态属性而不是每次读配置文件：关闭动作要即时、不能因为磁盘 I/O 卡一下。
    /// </summary>
    public static bool CloseToTray { get; set; } = true;

    /// <summary>已经决定退出（托盘菜单点了「退出」）。这时关闭窗口不再拦截。</summary>
    public bool IsExiting { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        // 窗口只是隐藏时不能退出：托盘里还要能点回来
        ShutdownMode = ShutdownMode.OnExplicitShutdown;

        // 先把兜底挂上，再让 base 去创建 StartupUri：
        // 这样 XAML 解析/依赖注入阶段的异常才有机会落盘，否则进程会静默退出。
        DispatcherUnhandledException += (_, args) =>
        {
            LogStartupFailure(args.Exception);
            MessageBox.Show(
                "界面线程出现未处理的异常：\n\n" + args.Exception.Message,
                "EchoLink",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            args.Handled = true;
        };

        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
            LogStartupFailure(args.ExceptionObject as Exception);

        try
        {
            base.OnStartup(e);
            CreateTray();
        }
        catch (Exception ex)
        {
            LogStartupFailure(ex);
            throw;
        }
    }

    private void CreateTray()
    {
        if (_tray is not null)
        {
            return;
        }

        _tray = new TrayIcon();
        _tray.ShowRequested += ShowMainWindow;
        _tray.ExitRequested += RequestExit;
    }

    /// <summary>把主窗口叫回来。窗口没关、只是隐藏，所以 Restore + Activate 就够。</summary>
    public void ShowMainWindow()
    {
        var window = MainWindow;
        if (window is null)
        {
            return;
        }

        window.Show();
        if (window.WindowState == WindowState.Minimized)
        {
            window.WindowState = WindowState.Normal;
        }

        window.Activate();
        window.Topmost = true;
        window.Topmost = false;   // 从托盘恢复时抢不到前台，这一步能把窗口提到最前
    }

    /// <summary>真正退出：先让关闭事件放行，关窗触发 ViewModel.Shutdown 停引擎，再结束应用。</summary>
    public void RequestExit()
    {
        IsExiting = true;

        // 关窗会走到 MainWindow.Closed → _viewModel.Shutdown()，那里会停引擎、存配置
        MainWindow?.Close();

        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _tray?.Dispose();
        _tray = null;
        base.OnExit(e);
    }

    /// <summary>第一次收进托盘时给一次提示，之后不再打扰。</summary>
    public void NotifyMinimizedToTray() => _tray?.ShowMinimizedHintOnce();

    private static void LogStartupFailure(Exception? exception)
    {
        if (exception is null)
        {
            return;
        }

        try
        {
            File.AppendAllText(
                StartupLogPath,
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {exception}{Environment.NewLine}{Environment.NewLine}");
        }
        catch
        {
            // 日志写不进去也不能再抛
        }
    }
}

