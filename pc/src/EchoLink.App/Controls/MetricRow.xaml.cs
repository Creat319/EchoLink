using System.Windows;
using System.Windows.Controls;

namespace EchoLink.App.Controls;

/// <summary>
/// 一行「标签 · 提示 …… 数值」。整个仪表盘有 20 多行，做成控件避免 XAML 里重复 20 份。
/// </summary>
public partial class MetricRow : UserControl
{
    public static readonly DependencyProperty LabelProperty = DependencyProperty.Register(
        nameof(Label), typeof(string), typeof(MetricRow), new PropertyMetadata(string.Empty));

    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(
        nameof(Value), typeof(string), typeof(MetricRow), new PropertyMetadata("-"));

    public static readonly DependencyProperty HintProperty = DependencyProperty.Register(
        nameof(Hint), typeof(string), typeof(MetricRow), new PropertyMetadata(string.Empty));

    public static readonly DependencyProperty ValueBrushProperty = DependencyProperty.Register(
        nameof(ValueBrush), typeof(System.Windows.Media.Brush), typeof(MetricRow),
        new PropertyMetadata(new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0xE4, 0xEB, 0xF2))));

    public MetricRow()
    {
        InitializeComponent();
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
        set => SetValue(HintProperty, value);
    }

    public System.Windows.Media.Brush ValueBrush
    {
        get => (System.Windows.Media.Brush)GetValue(ValueBrushProperty);
        set => SetValue(ValueBrushProperty, value);
    }
}
