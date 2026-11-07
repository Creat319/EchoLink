using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace EchoLink.App.Controls;

/// <summary>
/// 自绘电平条。刻意不用 ProgressBar：
/// MaterialDesign 的 ProgressBar 模板在 Value=0 时仍会画出一条满格的主色条，
/// 用在实时电平上会让人误以为一直在满幅输出。
/// </summary>
public partial class LevelMeter : UserControl
{
    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(
        nameof(Value), typeof(double), typeof(LevelMeter), new PropertyMetadata(0d));

    public static readonly DependencyProperty FillProperty = DependencyProperty.Register(
        nameof(Fill), typeof(Brush), typeof(LevelMeter),
        new PropertyMetadata(new SolidColorBrush(Color.FromRgb(0x64, 0xD2, 0xFF))));

    public LevelMeter()
    {
        InitializeComponent();
    }

    /// <summary>0..1 的比例。</summary>
    public double Value
    {
        get => (double)GetValue(ValueProperty);
        set => SetValue(ValueProperty, value);
    }

    public Brush Fill
    {
        get => (Brush)GetValue(FillProperty);
        set => SetValue(FillProperty, value);
    }
}
