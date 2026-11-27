using System;
using System.ComponentModel;
using EchoLink.App;
using EchoLink.App.ViewModels;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

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
        _viewModel.PropertyChanged += OnViewModelPropertyChanged;

        Title = "EchoLink · PC 发送端";

        // 关窗即退出进程，所以"关闭到托盘"必须在这里拦
        AppWindow.Closing += OnAppWindowClosing;

        // 首帧应用外观（主题 + 材质）。放在子控件都加载完之后。
        Root.Loaded += (_, _) =>
        {
            ApplyTheme();
            ApplyBackdrop();
            SyncNavSelection();
        };
    }

    /// <summary>设置页里显示的材质可用性说明。</summary>
    public string BackdropStatus { get; private set; } = string.Empty;

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        switch (e.PropertyName)
        {
            case nameof(MainViewModel.ThemeModeIndex):
                ApplyTheme();
                break;
            case nameof(MainViewModel.BackdropIndex):
                ApplyBackdrop();
                break;
            case nameof(MainViewModel.SelectedPageIndex):
                SyncNavSelection();
                break;
        }
    }

    // ---------------------------------------------------------------- 导航

    /// <summary>NavigationView → ViewModel。设置项（内置的齿轮）映射成索引 3。</summary>
    private void OnNavSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.IsSettingsSelected)
        {
            SetPage(3);
            return;
        }

        if (args.SelectedItem is NavigationViewItem { Tag: string tag })
        {
            SetPage(tag switch
            {
                "home" => 0,
                "advanced" => 1,
                "log" => 2,
                _ => 0,
            });
        }
    }

    private void SetPage(int index)
    {
        if (_viewModel.SelectedPageIndex != index)
        {
            _viewModel.SelectedPageIndex = index;
        }
    }

    /// <summary>ViewModel → NavigationView（例如别处改了页码时保持同步）。</summary>
    private void SyncNavSelection()
    {
        var index = _viewModel.SelectedPageIndex;
        if (index == 3)
        {
            if (!ReferenceEquals(Nav.SelectedItem, Nav.SettingsItem))
            {
                Nav.SelectedItem = Nav.SettingsItem;
            }

            return;
        }

        var target = index switch
        {
            1 => 1,
            2 => 2,
            _ => 0,
        };

        if (Nav.MenuItems.Count > target && !ReferenceEquals(Nav.SelectedItem, Nav.MenuItems[target]))
        {
            Nav.SelectedItem = Nav.MenuItems[target];
        }
    }

    // ---------------------------------------------------------------- 外观

    private void ApplyTheme()
    {
        Root.RequestedTheme = _viewModel.ThemeModeIndex switch
        {
            1 => ElementTheme.Light,
            2 => ElementTheme.Dark,
            _ => ElementTheme.Default,
        };
    }

    /// <summary>
    /// 应用窗口材质。Mica 需要 Windows 11；Windows 10 上只能毛玻璃（Acrylic）。
    /// 选择"云母"但系统不支持时自动退回毛玻璃，并把原因写进设置页的说明文字。
    /// </summary>
    private void ApplyBackdrop()
    {
        var want = _viewModel.BackdropIndex;
        string note;

        try
        {
            switch (want)
            {
                case 1: // 无
                    SystemBackdrop = null;
                    note = "当前使用纯色背景。";
                    break;

                case 2: // 云母
                    if (MicaController.IsSupported())
                    {
                        SystemBackdrop = new MicaBackdrop();
                        note = "当前使用云母（Mica）背景。";
                    }
                    else if (DesktopAcrylicController.IsSupported())
                    {
                        SystemBackdrop = new DesktopAcrylicBackdrop();
                        note = "这台系统不支持云母（需要 Windows 11），已自动改用毛玻璃。";
                    }
                    else
                    {
                        SystemBackdrop = null;
                        note = "这台系统既不支持云母也不支持毛玻璃，已使用纯色背景。";
                    }

                    break;

                case 3: // 毛玻璃
                    if (DesktopAcrylicController.IsSupported())
                    {
                        SystemBackdrop = new DesktopAcrylicBackdrop();
                        note = "当前使用毛玻璃（Acrylic）背景。";
                    }
                    else
                    {
                        SystemBackdrop = null;
                        note = "这台系统不支持毛玻璃，已使用纯色背景。";
                    }

                    break;

                default: // 自动
                    if (MicaController.IsSupported())
                    {
                        SystemBackdrop = new MicaBackdrop();
                        note = "自动：当前系统支持云母，已启用。";
                    }
                    else if (DesktopAcrylicController.IsSupported())
                    {
                        SystemBackdrop = new DesktopAcrylicBackdrop();
                        note = "自动：当前系统不支持云母（需要 Windows 11），已改用毛玻璃。";
                    }
                    else
                    {
                        SystemBackdrop = null;
                        note = "自动：当前系统没有可用材质，已使用纯色背景。";
                    }

                    break;
            }
        }
        catch (Exception ex)
        {
            SystemBackdrop = null;
            note = "应用材质失败，已回退纯色：" + ex.Message;
        }

        BackdropStatus = note;
        Bindings.Update();
    }

    // ---------------------------------------------------------------- 交互

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

    private void OnExitClicked(object sender, RoutedEventArgs e) => (Application.Current as App)?.RequestExit();
}
