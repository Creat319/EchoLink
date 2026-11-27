using System.Windows;
using System.Windows.Threading;

namespace EchoLink.App;

/// <summary>
/// WPF 宿主对 <see cref="IUiBridge"/> 的实现。
///
/// ViewModel 在共用库里，不认识 WPF；这里把 WPF 的 Dispatcher / MessageBox /
/// SaveFileDialog 包进去，WinUI 版会提供另一个实现。
/// </summary>
internal sealed class WpfUiBridge : IUiBridge
{
    private readonly Dispatcher _dispatcher;

    public WpfUiBridge(Dispatcher dispatcher) => _dispatcher = dispatcher;

    public void Post(Action action)
    {
        if (_dispatcher.CheckAccess())
        {
            action();
            return;
        }

        _dispatcher.BeginInvoke(action, DispatcherPriority.Background);
    }

    public void ShowWarning(string title, string message)
    {
        MessageBox.Show(message, title, MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    public string? PickSaveFile(string title, string filter, string defaultFileName)
    {
        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            Title = title,
            Filter = filter,
            FileName = defaultFileName,
            InitialDirectory = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
        };

        return dialog.ShowDialog() == true ? dialog.FileName : null;
    }
}
