using System.Net.Http;
using System.Text.Json;

namespace EchoLink.Core.Update;

/// <summary>服务端返回的版本信息。</summary>
public sealed record UpdateInfo(string Version, int Code, string Scope)
{
    public bool IsUsable => Code == 0 && !string.IsNullOrWhiteSpace(Version);
}

/// <summary>
/// 版本自检。查一个极简的版本接口，把服务端版本和本地版本比较，供 UI 提示"有新版本"。
///
/// 只做**提示 + 打开下载页**，不自动下载、不自动运行：更新包是 zip/exe，
/// 自动执行等于把更新通道变成远程代码执行入口。这一点是有意为之。
/// </summary>
public static class UpdateChecker
{
    /// <summary>版本接口。key 是公开在客户端的，只能当"粗粒度防扫描"，不算机密。</summary>
    public const string Endpoint =
        "http://carovo.shop/api/version.php?format=json&key=ae5d71d171e39cc04defc29293506fc0";

    public const string WindowsDownloadUrl = "http://el.carovo.shop/winclient.zip";

    public const string AndroidDownloadUrl = "http://el.carovo.shop/echolink-arm64-debug.apk";

    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(6),
    };

    /// <summary>查询最新版本。任何失败（离网、超时、返回异常）都返回 null，不影响主流程。</summary>
    public static async Task<UpdateInfo?> CheckAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await Http.GetAsync(Endpoint, cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }

            var json = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;

            var code = root.TryGetProperty("code", out var codeElement) && codeElement.TryGetInt32(out var parsed)
                ? parsed
                : -1;
            var version = root.TryGetProperty("version", out var versionElement)
                ? versionElement.GetString() ?? string.Empty
                : string.Empty;
            var scope = root.TryGetProperty("scope", out var scopeElement)
                ? scopeElement.GetString() ?? string.Empty
                : string.Empty;

            var info = new UpdateInfo(version, code, scope);
            return info.IsUsable ? info : null;
        }
        catch (Exception)
        {
            // 更新检查是锦上添花，绝不能让主流程报错
            return null;
        }
    }

    /// <summary>remote 是否比 local 新。</summary>
    public static bool IsNewer(string? remote, string? local)
        => CompareVersions(remote, local) > 0;

    /// <summary>
    /// 逐段比较点分版本号。非数字段按 0 处理，缺段按 0 补齐，
    /// 这样 "0.3.10" 会正确大于 "0.3.9"（字符串比较会搞错）。
    /// </summary>
    public static int CompareVersions(string? left, string? right)
    {
        var leftParts = Split(left);
        var rightParts = Split(right);
        var length = Math.Max(leftParts.Length, rightParts.Length);

        for (var i = 0; i < length; i++)
        {
            var a = i < leftParts.Length ? leftParts[i] : 0;
            var b = i < rightParts.Length ? rightParts[i] : 0;
            if (a != b)
            {
                return a > b ? 1 : -1;
            }
        }

        return 0;
    }

    private static int[] Split(string? version)
    {
        if (string.IsNullOrWhiteSpace(version))
        {
            return Array.Empty<int>();
        }

        var text = version.Trim().TrimStart('v', 'V');
        var pieces = text.Split('.', StringSplitOptions.RemoveEmptyEntries);
        var result = new int[pieces.Length];
        for (var i = 0; i < pieces.Length; i++)
        {
            // "0-beta" 这类后缀直接当 0，不抛异常
            var digits = new string(pieces[i].TakeWhile(char.IsDigit).ToArray());
            result[i] = int.TryParse(digits, out var value) ? value : 0;
        }

        return result;
    }
}
