using System.Windows.Media;

namespace EchoLink.App.Models;

/// <summary>渲染端点下拉项。<see cref="Id"/> 为 null 表示“跟随系统默认输出”。</summary>
public sealed class DeviceItem
{
    public DeviceItem(string name, string? id, bool isDefault = false, bool isVirtual = false)
    {
        Name = name;
        Id = id;
        IsDefault = isDefault;
        IsVirtual = isVirtual;
    }

    public string Name { get; }

    public string? Id { get; }

    /// <summary>是否系统当前默认输出端点。</summary>
    public bool IsDefault { get; }

    /// <summary>是否是虚拟声卡（VB-Cable / AudioRelay 之类），用来给个提示。</summary>
    public bool IsVirtual { get; }

    public Brush Accent => IsDefault ? Brushes.LimeGreen : Brushes.Transparent;

    public override string ToString() => Name;
}
