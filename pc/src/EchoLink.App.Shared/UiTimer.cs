using System.Threading;

namespace EchoLink.App;

/// <summary>
/// 周期性回调定时器，回调在 UI 线程上执行。
///
/// 用来替代 WPF 的 <c>DispatcherTimer</c>：那个类型属于 WPF，WinUI 里叫
/// <c>DispatcherQueueTimer</c>，两者又不通用。这里用 <see cref="Timer"/> + 宿主提供的
/// <see cref="UiBridge.Post"/>，一份代码两边都能跑。
///
/// 刻意加了"上一轮没跑完就跳过本轮"的保护：250 ms 的快照刷新如果偶发卡顿，
/// 不该让回调在队列里堆积。
/// </summary>
public sealed class UiTimer : IDisposable
{
    private readonly TimeSpan _period;
    private readonly Action _tick;
    private readonly Timer _timer;
    private int _inFlight;

    public UiTimer(TimeSpan period, Action tick)
    {
        _period = period;
        _tick = tick;
        _timer = new Timer(OnElapsed, null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start() => _timer.Change(_period, _period);

    public void Stop() => _timer.Change(Timeout.Infinite, Timeout.Infinite);

    private void OnElapsed(object? state)
    {
        // 0 → 1 才继续；已有回调在排队就跳过，避免堆积
        if (Interlocked.Exchange(ref _inFlight, 1) == 1)
        {
            return;
        }

        try
        {
            UiBridge.Current.Post(() =>
            {
                try
                {
                    _tick();
                }
                finally
                {
                    Interlocked.Exchange(ref _inFlight, 0);
                }
            });
        }
        catch
        {
            Interlocked.Exchange(ref _inFlight, 0);
        }
    }

    public void Dispose() => _timer.Dispose();
}
