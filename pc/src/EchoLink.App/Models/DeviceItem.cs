using System.Windows.Media;

namespace EchoLink.App.Models;

/// <summary>渲染端点下拉项。<see cref="Id"/> 为 null 表示“跟随系统默认输出”。</summary>
public sealed class DeviceItem
{
    public DeviceItem(string name, string? id, bool isDefault = false, bool isVirtual = false, double minPeriodMs = 0, double defaultPeriodMs = 0)
    {
        Name = name;
        Id = id;
        IsDefault = isDefault;
        IsVirtual = isVirtual;
        MinPeriodMs = minPeriodMs;
        DefaultPeriodMs = defaultPeriodMs;
    }

    public string Name { get; }

    public string? Id { get; }

    /// <summary>是否系统当前默认输出端点。</summary>
    public bool IsDefault { get; }

    /// <summary>是否是虚拟声卡（VB-Cable / AudioRelay 之类），用来给个提示。</summary>
    public bool IsVirtual { get; }

    /// <summary>该端点能拿到的最小共享模式引擎周期（ms），0 表示未探测到。</summary>
    public double MinPeriodMs { get; }

    /// <summary>默认引擎周期（ms）。</summary>
    public double DefaultPeriodMs { get; }

    /// <summary>该端点能否给出比传统 10 ms 更小的抓取粒度。</summary>
    public bool SupportsSmallPeriod => MinPeriodMs > 0 && MinPeriodMs < 9.9;

    public Brush Accent => IsDefault ? Brushes.LimeGreen : Brushes.Transparent;

    /// <summary>下拉里直接标出小周期能力，用户才知道该选哪个端点。</summary>
    public string DisplayName
    {
        get
        {
            if (MinPeriodMs <= 0)
            {
                return Name;
            }

            var tag = SupportsSmallPeriod
                ? $"小周期 {MinPeriodMs:F2} ms"
                : $"周期 {MinPeriodMs:F2} ms";

            return $"{Name}   ·  {tag}";
        }
    }

    public override string ToString() => DisplayName;
}
