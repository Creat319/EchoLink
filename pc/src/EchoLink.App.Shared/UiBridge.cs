namespace EchoLink.App;

/// <summary>
/// 界面宿主必须提供的最小能力集合。
///
/// WPF 和 WinUI 的线程模型、弹窗 API 都不同，ViewModel 不该知道自己跑在哪个框架上，
/// 所以只依赖这三个动作。默认实现是"直接执行 + 不弹窗"，方便单测与非 UI 场景。
/// </summary>
public interface IUiBridge
{
    /// <summary>在 UI 线程上执行。可从任意线程调用。</summary>
    void Post(Action action);

    /// <summary>弹一个警告框（阻塞）。</summary>
    void ShowWarning(string title, string message);

    /// <summary>弹文件保存对话框；取消返回 null。</summary>
    string? PickSaveFile(string title, string filter, string defaultFileName);
}

/// <summary>当前宿主实现的桥。启动时由各 UI 项目注入。</summary>
public static class UiBridge
{
    public static IUiBridge Current { get; set; } = new HeadlessUiBridge();

    private sealed class HeadlessUiBridge : IUiBridge
    {
        public void Post(Action action) => action();

        // 没有界面就静默；日志里已经写过同样的信息
        public void ShowWarning(string title, string message)
        {
        }

        public string? PickSaveFile(string title, string filter, string defaultFileName) => null;
    }
}
