using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace EchoLink.App.WinUI.Controls;

/// <summary>
/// 一行「标签 · 提示 —— 数值」。整个仪表盘有二十多行，做成控件避免 XAML 里重复二十份。
/// </summary>
public sealed partial class MetricRowControl : UserControl
{
    public static readonly DependencyProperty LabelProperty = DependencyProperty.Register(
        nameof(Label), typeof(string), typeof(MetricRowControl), new PropertyMetadata(string.Empty));

    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(
        nameof(Value), typeof(string), typeof(MetricRowControl), new PropertyMetadata("-"));

    public static readonly DependencyProperty HintProperty = DependencyProperty.Register(
        nameof(Hint), typeof(string), typeof(MetricRowControl), new PropertyMetadata(string.Empty));

    public MetricRowControl()
    {
        InitializeComponent();
        UpdateHintVisibility();
    }

    public string Label
    {
        get => (string)GetValue(LabelProperty);
        set => SetValue(LabelProperty, value);
    }

    public string Value
    {
        get => (string)GetValue(ValueProperty);
        set => SetValue(ValueProperty, value);
    }

    public string Hint
    {
        get => (string)GetValue(HintProperty);
        set
        {
            SetValue(HintProperty, value);
            UpdateHintVisibility();
        }
    }

    /// <summary>没有提示文字时把那一行隐藏掉，不然会留一条空白。</summary>
    private void UpdateHintVisibility()
    {
        if (HintText is null)
        {
            return;
        }

        HintText.Visibility = string.IsNullOrWhiteSpace(Hint) ? Visibility.Collapsed : Visibility.Visible;
    }
}
