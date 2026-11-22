using System.ComponentModel;
using System.Windows;
using EchoLink.App.ViewModels;

namespace EchoLink.App;

public partial class MainWindow : Window
{
    private readonly MainViewModel _viewModel;

    public MainWindow()
    {
        InitializeComponent();

        _viewModel = (MainViewModel)DataContext;
        _viewModel.LogUpdated += ScrollLogToEnd;
        Closed += (_, _) => _viewModel.Shutdown();

        // 默认尺寸可能比工作区还高（1080p + 任务栏），先按工作区收敛再居中，
        // 否则窗口底部会被任务栏挡住。
        FitToWorkArea();
    }

    /// <summary>
    /// 点关闭时不退出，收进托盘继续放音频。
    ///
    /// 这是个长时间开着的音频工具，关窗多半只是想把它从眼前挪开；
    /// 真正退出走托盘菜单的「退出 EchoLink」。
    /// 想恢复旧行为（点关闭就退出）可以在设置里关掉「关闭时最小化到托盘」。
    /// </summary>
    protected override void OnClosing(CancelEventArgs e)
    {
        if (!App.CloseToTray)
        {
            base.OnClosing(e);
            return;
        }

        if (Application.Current is App { IsExiting: true })
        {
            base.OnClosing(e);
            return;
        }

        e.Cancel = true;
        Hide();

        // 不提示的话用户会以为程序退了，然后发现音频还在响、又找不到窗口
        (Application.Current as App)?.NotifyMinimizedToTray();

        base.OnClosing(e);
    }

    private void FitToWorkArea()
    {
        var work = SystemParameters.WorkArea;
        Width = Math.Min(Width, work.Width - 40);
        Height = Math.Min(Height, work.Height - 40);
        Left = work.Left + ((work.Width - Width) / 2);
        Top = work.Top + ((work.Height - Height) / 2);
    }

    /// <summary>日志是只读 TextBox + 字符串绑定，追加后把光标挪到末尾即可自动跟到底部。</summary>
    private void ScrollLogToEnd()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(ScrollLogToEnd);
            return;
        }

        LogBox.ScrollToEnd();
    }

    private void OnClearLogClicked(object sender, RoutedEventArgs e) => _viewModel.ClearLog();

    /// <summary>
    /// 应用内退出。托盘图标在 Win10/11 默认收进溢出区，不容易找，
    /// 这里给一个明确入口；关窗口本身只是隐藏。
    /// </summary>
    private void OnExitClicked(object sender, RoutedEventArgs e)
    {
        if (Application.Current is App app)
        {
            app.RequestExit();
        }
    }
}
