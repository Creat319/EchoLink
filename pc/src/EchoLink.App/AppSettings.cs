using System.IO;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace EchoLink.App;

/// <summary>
/// 用户设置的持久化模型，落在 %APPDATA%\EchoLink\settings.json。
/// 设计原则：读写都"尽量不抛"——设置文件损坏/无权限时静默回退默认值，
/// 绝不因为一个 json 文件让界面起不来。
/// </summary>
public sealed class AppSettings
{
    // Relaxed 编码：中文按原字符写盘，用户自己打开 settings.json 也能看懂
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    // ---------------- 输出设备 ----------------
    public string? DeviceId { get; set; }

    public string DeviceName { get; set; } = "系统默认输出";

    // ---------------- 引擎 ----------------
    /// <summary>引擎模式下拉框序号：0=自动，1=路径 A，2=仅兜底 legacy。</summary>
    public int ModeIndex { get; set; }

    public bool PreferSmallPeriodKeepAlive { get; set; } = true;

    public bool EnableKeepAlive { get; set; } = true;

    // ---------------- 手机地址 ----------------
    public bool AutoDiscoverTarget { get; set; } = true;

    public string ManualTargetAddress { get; set; } = string.Empty;

    public int AudioPort { get; set; } = 47810;

    public int ReportPort { get; set; } = 47811;

    // ---------------- 诊断 ----------------
    public bool WavDumpEnabled { get; set; }

    public string WavDumpPath { get; set; } = string.Empty;

    public static string SettingsDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "EchoLink");

    public static string SettingsFilePath => Path.Combine(SettingsDirectory, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            var path = SettingsFilePath;
            if (!File.Exists(path))
            {
                return new AppSettings();
            }

            var json = File.ReadAllText(path);
            if (string.IsNullOrWhiteSpace(json))
            {
                return new AppSettings();
            }

            return JsonSerializer.Deserialize<AppSettings>(json, SerializerOptions) ?? new AppSettings();
        }
        catch
        {
            // 损坏、被别的进程占用、权限不足……一律当作"没有设置"
            return new AppSettings();
        }
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(SettingsDirectory);
            var json = JsonSerializer.Serialize(this, SerializerOptions);
            File.WriteAllText(SettingsFilePath, json);
        }
        catch
        {
            // 保存失败不上抛：关窗时再弹错误框只会让用户更困惑
        }
    }
}
