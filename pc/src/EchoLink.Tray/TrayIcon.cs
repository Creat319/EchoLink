using System;
using System.Drawing;
using WinForms = System.Windows.Forms;
using DrawingFont = System.Drawing.Font;
using DrawingFontStyle = System.Drawing.FontStyle;

namespace EchoLink.Tray;

/// <summary>
/// 系统托盘图标。
///
/// 用 WinForms 的 <see cref="WinForms.NotifyIcon"/> 而不是自己 P/Invoke
/// Shell_NotifyIcon：后者要自己处理窗口消息、菜单弹出坐标和图标销毁，
/// 在 WPF 里还要额外造一个隐藏窗口收消息，不值得。
///
/// 图标直接取 exe 自带的（csproj 里 ApplicationIcon=app.ico），
/// 这样托盘、任务栏、文件管理器里永远是同一张图。
/// </summary>
public sealed class TrayIcon : IDisposable
{
    private readonly WinForms.NotifyIcon _notifyIcon;
    private readonly WinForms.ToolStripMenuItem _showItem;
    private bool _hintShown;

    public TrayIcon()
    {
        var menu = new WinForms.ContextMenuStrip();

        _showItem = new WinForms.ToolStripMenuItem("显示主界面", null, (_, _) => ShowRequested?.Invoke())
        {
            Font = new DrawingFont(menu.Font, DrawingFontStyle.Bold),
        };

        var exitItem = new WinForms.ToolStripMenuItem("退出 EchoLink", null, (_, _) => ExitRequested?.Invoke());

        menu.Items.Add(_showItem);
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add(exitItem);

        _notifyIcon = new WinForms.NotifyIcon
        {
            Icon = LoadAppIcon(),
            Text = "EchoLink · 手机当电脑音响",
            Visible = true,
            ContextMenuStrip = menu,
        };

        // 单击和双击都还原窗口：很多人习惯单击，而双击会被系统当成两次单击前的等待，
        // 两种都接管最省事。
        _notifyIcon.MouseClick += (_, e) =>
        {
            if (e.Button == WinForms.MouseButtons.Left)
            {
                ShowRequested?.Invoke();
            }
        };

        _notifyIcon.DoubleClick += (_, _) => ShowRequested?.Invoke();
    }

    public event Action? ShowRequested;

    public event Action? ExitRequested;

    /// <summary>更新托盘提示文字（例如显示当前链路状态）。</summary>
    public void SetTooltip(string text)
    {
        // NotifyIcon.Text 上限 63 字符，超了会抛异常
        if (text.Length > 63)
        {
            text = text[..60] + "…";
        }

        _notifyIcon.Text = text;
    }

    /// <summary>
    /// 第一次收进托盘时提示一次，否则用户会以为程序退了。
    /// 只在本次运行内提示一次，不写配置——重复提示比不提示更烦。
    /// </summary>
    public void ShowMinimizedHintOnce()
    {
        if (_hintShown)
        {
            return;
        }

        _hintShown = true;
        try
        {
            _notifyIcon.ShowBalloonTip(
                3000,
                "EchoLink 仍在后台运行",
                "音频没有中断。要真正退出，请右键托盘图标选择「退出 EchoLink」。",
                WinForms.ToolTipIcon.Info);
        }
        catch
        {
            // 系统关了通知（专注助手等）时这里可能失败，忽略即可
        }
    }

    private static Icon LoadAppIcon()
    {
        try
        {
            // 与 exe 同一张图：托盘/任务栏/资源管理器看起来才一致
            var path = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(path))
            {
                var icon = Icon.ExtractAssociatedIcon(path);
                if (icon is not null)
                {
                    return icon;
                }
            }
        }
        catch
        {
            // 落到下面的兜底
        }

        return SystemIcons.Application;
    }

    public void Dispose()
    {
        // 必须先设 Visible=false，否则退出后托盘里会残留一个"幽灵图标"
        _notifyIcon.Visible = false;
        _notifyIcon.ContextMenuStrip?.Dispose();
        _notifyIcon.Dispose();
    }
}
