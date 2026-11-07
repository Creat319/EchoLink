using System.IO;
using System.Windows;

namespace EchoLink.App;

public partial class App : Application
{
    private static readonly string StartupLogPath =
        Path.Combine(Path.GetTempPath(), "echolink-startup.log");

    protected override void OnStartup(StartupEventArgs e)
    {
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
        }
        catch (Exception ex)
        {
            LogStartupFailure(ex);
            throw;
        }
    }

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

