using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace EchoLink.App.WinUI;

/// <summary>bool → Visibility。</summary>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    /// <summary>把 Invert 作为 ConverterParameter 传入即取反（"Invert"）。</summary>
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        var flag = value is bool b && b;
        if (parameter is string text && text.Equals("Invert", StringComparison.OrdinalIgnoreCase))
        {
            flag = !flag;
        }

        return flag ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language)
        => throw new NotSupportedException();
}

/// <summary>
/// 十六进制颜色字符串 → 画刷。
/// ViewModel 在共用库里不能引用任何 UI 框架的画刷类型，所以颜色以 "#RRGGBB" 暴露。
/// </summary>
public sealed class HexToBrushConverter : IValueConverter
{
    private static readonly SolidColorBrush Transparent = new(Microsoft.UI.Colors.Transparent);

    public object Convert(object value, Type targetType, object parameter, string language)
    {
        if (value is not string text || string.IsNullOrWhiteSpace(text) ||
            text.Equals("Transparent", StringComparison.OrdinalIgnoreCase))
        {
            return Transparent;
        }

        try
        {
            var hex = text.TrimStart('#');
            if (hex.Length == 6)
            {
                var r = System.Convert.ToByte(hex[..2], 16);
                var g = System.Convert.ToByte(hex.Substring(2, 2), 16);
                var b = System.Convert.ToByte(hex.Substring(4, 2), 16);
                return new SolidColorBrush(Color.FromArgb(255, r, g, b));
            }
        }
        catch (FormatException)
        {
        }
        catch (OverflowException)
        {
        }

        return Transparent;
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language)
        => throw new NotSupportedException();
}

/// <summary>
/// bool → 两个颜色之一。ConverterParameter 形如 "#FFC46B|#93A4B8"（真|假）。
/// WinUI 没有 DataTrigger，这类"按状态换颜色"只能走转换器。
/// </summary>
public sealed class BoolToBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        var flag = value is bool b && b;
        var spec = parameter as string ?? "#FFC46B|#93A4B8";
        var parts = spec.Split('|');
        var chosen = flag ? parts[0] : parts.Length > 1 ? parts[1] : parts[0];
        return new HexToBrushConverter().Convert(chosen, typeof(Brush), string.Empty, string.Empty);
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language)
        => throw new NotSupportedException();
}

/// <summary>数值 0–1 → 宽度比例（电平条用）。</summary>
public sealed class FractionWidthConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        var fraction = value is double d ? Math.Clamp(d, 0, 1) : 0;
        var max = parameter is string text && double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var m)
            ? m
            : 200;
        return fraction * max;
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language)
        => throw new NotSupportedException();
}
