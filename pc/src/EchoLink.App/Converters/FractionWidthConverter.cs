using System.Globalization;
using System.Windows.Data;

namespace EchoLink.App.Converters;

/// <summary>把 0..1 的比例乘以一个像素宽度，用于自绘电平条（不依赖 ProgressBar 模板）。</summary>
public sealed class FractionWidthConverter : IMultiValueConverter
{
    public object Convert(object[] values, Type targetType, object parameter, CultureInfo culture)
    {
        if (values.Length < 2)
        {
            return 0d;
        }

        var fraction = values[0] is double f ? f : 0d;
        var total = values[1] is double w ? w : 0d;
        if (double.IsNaN(total) || double.IsNaN(fraction) || total <= 0)
        {
            return 0d;
        }

        return Math.Clamp(fraction, 0d, 1d) * total;
    }

    public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
