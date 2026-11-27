using EchoLink.App;
using EchoLink.Tray;
using Microsoft.UI.Xaml;

namespace EchoLink.App.WinUI;

public partial class App : Application
{
    private static readonly string CrashLogPath =
        System.IO.Path.Combine(System.IO.Path.GetTempPath(), "echolink-winui-crash.log");

    private MainWindow? _window;
    private TrayIcon? _tray;

    public App()
    {
        InitializeComponent();

        // WinUI 的 XAML 加载异常不会出现在控制台里，必须自己落盘
        UnhandledException += (_, e) =>
        {
            Log(e.Exception);
            e.Handled = true;
        };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            // ViewModel 在共用库里，通过桥拿到 WinUI 的 DispatcherQueue 与弹窗能力
            var queue = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread();
            UiBridge.Current = new WinUiBridge(queue);

            _window = new MainWindow();
            _window.Activate();

            CreateTray();
        }
        catch (Exception ex)
        {
            Log(ex);
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

    public void ShowMainWindow()
    {
        if (_window is null)
        {
            return;
        }

        _window.AppWindow.Show();
        _window.Activate();
    }

    /// <summary>真正退出：关窗口会让 ViewModel 停引擎并保存配置。</summary>
    public void RequestExit()
    {
        _window?.Close();
        _tray?.Dispose();
        _tray = null;
        Exit();
    }

    /// <summary>第一次收进托盘时提示一次，之后不再打扰。</summary>
    public void NotifyMinimizedToTray() => _tray?.ShowMinimizedHintOnce();

    private static void Log(Exception? ex)
    {
        if (ex is null)
        {
            return;
        }

        try
        {
            System.IO.File.AppendAllText(
                CrashLogPath,
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {ex}{Environment.NewLine}{Environment.NewLine}");
        }
        catch
        {
        }
    }
}
