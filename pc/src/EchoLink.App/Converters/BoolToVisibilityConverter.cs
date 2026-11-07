using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace EchoLink.App.Converters;

/// <summary>bool → Visibility；<see cref="Invert"/> 为 true 时取反（用于“没有数据显示提示”这类场景）。</summary>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    public bool Invert { get; set; }

    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var flag = value is bool b && b;
        if (Invert)
        {
            flag = !flag;
        }

        return flag ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
