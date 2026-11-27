using System;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using EchoLink.App;

namespace EchoLink.App.WinUI;

/// <summary>WinUI 宿主对共用 ViewModel 桥的实现。</summary>
internal sealed class WinUiBridge : IUiBridge
{
    private readonly DispatcherQueue _dispatcher;

    public WinUiBridge(DispatcherQueue dispatcher) => _dispatcher = dispatcher;

    public void Post(Action action)
    {
        if (_dispatcher.HasThreadAccess)
        {
            action();
            return;
        }

        _dispatcher.TryEnqueue(DispatcherQueuePriority.Low, () => action());
    }

    public void ShowWarning(string title, string message)
    {
        // WinUI 的 ContentDialog 必须 await 且要指定 XamlRoot，无法在同步接口里弹；
        // 这里退化为不阻塞的提示：把信息交给调用方日志已覆盖的部分。
        // 需要真正弹窗的调用点（目前只有"启动失败"）在窗口里单独处理。
        LastWarning = (title, message);
    }

    public string? PickSaveFile(string title, string filter, string defaultFileName)
    {
        // WinUI 用 FileSavePicker（异步 + 需要窗口句柄），同步接口里返回 null。
        // 高级页的「选择…」按钮改为直接调用异步实现，见 MainWindow.OnBrowseWav。
        return null;
    }

    /// <summary>最近一次未展示的警告，窗口可以取走并用 ContentDialog 显示。</summary>
    public (string Title, string Message)? LastWarning { get; private set; }
}
