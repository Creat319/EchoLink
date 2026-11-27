namespace EchoLink.App;

/// <summary>
/// 窗口层面的设置。放在共用库里，是因为 ViewModel 需要读写它，
/// 而"窗口"本身属于各 UI 项目（WPF 的 Window / WinUI 的 Window 完全不是一回事）。
/// </summary>
public static class ShellSettings
{
    /// <summary>点关闭时收进托盘而不是退出。</summary>
    public static bool CloseToTray { get; set; } = true;
}
