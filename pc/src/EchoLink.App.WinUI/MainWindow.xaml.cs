using System;
using EchoLink.App.ViewModels;
using Microsoft.UI.Xaml;

namespace EchoLink.App.WinUI;

public sealed partial class MainWindow : Window
{
    private readonly MainViewModel _viewModel;

    public MainWindow()
    {
        InitializeComponent();

        _viewModel = new MainViewModel();
        Root.DataContext = _viewModel;
        _viewModel.LogUpdated += ScrollLogToEnd;

        Title = "EchoLink · PC 发送端";

        // WinUI 关窗即退出进程，所以"关闭到托盘"必须在这里拦：
        // 这是长时间挂着的音频工具，关窗多半只是想把它从眼前挪开。
        AppWindow.Closing += OnAppWindowClosing;
    }

    private void OnAppWindowClosing(Microsoft.UI.Windowing.AppWindow sender,
                                    Microsoft.UI.Windowing.AppWindowClosingEventArgs args)
    {
        if (!ShellSettings.CloseToTray)
        {
            return;
        }

        args.Cancel = true;
        sender.Hide();
        (Application.Current as App)?.NotifyMinimizedToTray();
    }

    private void ScrollLogToEnd()
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            LogBox.SelectionStart = LogBox.Text.Length;
            LogBox.SelectionLength = 0;
        });
    }

    private async void OnBrowseWavClicked(object sender, RoutedEventArgs e)
    {
        var picker = new Windows.Storage.Pickers.FileSavePicker
        {
            SuggestedFileName = $"echolink-{DateTime.Now:yyyyMMdd-HHmmss}",
        };
        picker.FileTypeChoices.Add("WAV 音频", new[] { ".wav" });

        // WinUI 的 picker 必须先初始化窗口句柄
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);

        var file = await picker.PickSaveFileAsync();
        if (file is not null)
        {
            _viewModel.WavDumpPath = file.Path;
            _viewModel.WavDumpEnabled = true;
        }
    }

    private void OnClearLogClicked(object sender, RoutedEventArgs e) => _viewModel.ClearLog();

    private void OnExitClicked(object sender, RoutedEventArgs e) => (App.Current as App)?.RequestExit();
}
