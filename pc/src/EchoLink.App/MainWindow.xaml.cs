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
}
