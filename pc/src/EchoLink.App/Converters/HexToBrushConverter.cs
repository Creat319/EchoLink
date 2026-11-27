using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;

namespace EchoLink.App.Converters;

/// <summary>
/// 十六进制颜色字符串 → 画刷。
///
/// ViewModel 在共用库里，不能引用 WPF 或 WinUI 的画刷类型，所以颜色以 "#RRGGBB"
/// 字符串暴露；到各自的 XAML 里再用这个转换器转成本框架的画刷。
/// 支持 "Transparent" 字样与 null（返回透明）。
/// </summary>
public sealed class HexToBrushConverter : IValueConverter
{
    private static readonly Brush Transparent = CreateTransparent();

    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not string text || string.IsNullOrWhiteSpace(text))
        {
            return Transparent;
        }

        if (text.Equals("Transparent", StringComparison.OrdinalIgnoreCase))
        {
            return Transparent;
        }

        try
        {
            var color = (Color)ColorConverter.ConvertFromString(text);
            var brush = new SolidColorBrush(color);
            brush.Freeze();
            return brush;
        }
        catch (FormatException)
        {
            return Transparent;
        }
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => Binding.DoNothing;

    private static Brush CreateTransparent()
    {
        var brush = new SolidColorBrush(Colors.Transparent);
        brush.Freeze();
        return brush;
    }
}
