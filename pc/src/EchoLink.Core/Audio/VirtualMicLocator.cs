using NAudio.CoreAudioApi;

namespace EchoLink.Core.Audio;

/// <summary>
/// 一个可用的"虚拟麦克风"候选：往 <see cref="RenderId"/> 渲染，会议软件从 <see cref="CaptureName"/> 录。
/// </summary>
public sealed record VirtualMicCandidate(
    string RenderId,
    string RenderName,
    string? CaptureName,
    int Score,
    string Reason)
{
    /// <summary>有配对的录音端点才算可用——否则会议软件录不到。</summary>
    public bool Usable => CaptureName is not null;

    public bool IsDefaultCapture { get; init; }

    public override string ToString()
        => Usable
            ? $"{RenderName} → {CaptureName}{(string.IsNullOrEmpty(Reason) ? string.Empty : "（" + Reason + "）")}"
            : $"{RenderName}（无配对录音端点）";
}

/// <summary>
/// 自动寻找本机的虚拟声卡对（渲染侧 + 录音侧）。
///
/// 为什么必须靠"已安装的驱动"：Windows 没有用户态音频驱动框架，虚拟麦克风
/// 只能是内核驱动。应用侧能做的是**把已装的虚拟声卡找出来并配对好**，
/// 而不是凭空创建。没装的时候只能引导用户装（见 UI 里的一键安装入口）。
///
/// 判据不是名字里有没有 "cable" 就够了——还要能找到**同名的录音端点**，
/// 因为会议软件那边是从录音端点取的。只有渲染侧没有录音侧的"虚拟声卡"没法当麦克风用。
/// </summary>
public static class VirtualMicLocator
{
    /// <summary>常见的虚拟声卡名字特征，命中越多分越高。</summary>
    private static readonly (string Token, int Score, string Reason)[] NameHints =
    {
        ("cable input", 100, "VB-Cable 输入"),
        ("cable", 90, "VB-Cable"),
        ("vb-audio", 80, "VB-Audio"),
        ("voicemeeter", 85, "Voicemeeter"),
        ("virtual mic", 70, "虚拟麦克风"),
        ("virtual audio", 65, "虚拟声卡"),
        ("virtual speaker", 55, "虚拟扬声器"),
        ("audiorelay", 50, "AudioRelay 虚拟设备"),
        ("虚拟", 55, "虚拟设备"),
        ("blackhole", 60, "BlackHole"),
        ("soundflower", 60, "Soundflower"),
    };

    /// <summary>明显不是虚拟声卡的端点，直接排除。</summary>
    private static readonly string[] Exclusions =
    {
        "hdmi", "displayport", "nvidia", "amd high definition", "realtek digital",
        "s/pdif", "spdif", "耳机", "扬声器 (realtek",
    };

    /// <summary>
    /// 列出候选，按分数降序。第一个就是最该自动选用的那个。
    /// 只做设备枚举，不开流、不探测周期，可以随时调用（比如每几秒重扫）。
    /// </summary>
    public static IReadOnlyList<VirtualMicCandidate> Locate()
    {
        var results = new List<VirtualMicCandidate>();

        try
        {
            using var enumerator = new MMDeviceEnumerator();

            string? defaultCaptureId = null;
            try
            {
                defaultCaptureId = enumerator.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Multimedia).ID;
            }
            catch
            {
                // 没有默认录音设备不影响扫描
            }

            var captures = new List<(string Id, string Name)>();
            foreach (var device in enumerator.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active))
            {
                try
                {
                    captures.Add((device.ID, device.FriendlyName));
                }
                catch
                {
                    // 个别设备读不到名字就跳过
                }
            }

            foreach (var device in enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
            {
                string id;
                string name;
                try
                {
                    id = device.ID;
                    name = device.FriendlyName;
                }
                catch
                {
                    continue;
                }

                var lower = name.ToLowerInvariant();
                if (Exclusions.Any(lower.Contains))
                {
                    continue;
                }

                var score = 0;
                var reasons = new List<string>();
                foreach (var (token, weight, reason) in NameHints)
                {
                    if (!lower.Contains(token) && !name.Contains(token))
                    {
                        continue;
                    }

                    score += weight;
                    if (!reasons.Contains(reason))
                    {
                        reasons.Add(reason);
                    }

                    break;
                }

                // 配对的录音端点：名字去掉"输入/输出/Input/Output"后相同，或直接同名前缀
                var (captureId, captureName) = MatchCapture(name, captures);
                if (captureName is not null)
                {
                    score += 40;
                    reasons.Add("已配对录音端点");
                }

                if (score <= 0)
                {
                    continue;
                }

                results.Add(new VirtualMicCandidate(
                        id,
                        name,
                        captureName,
                        score,
                        string.Join("、", reasons))
                {
                    IsDefaultCapture = captureId is not null && captureId == defaultCaptureId,
                });
            }
        }
        catch
        {
            // 枚举失败就是"没找到"，交给 UI 引导安装
        }

        return results
            .OrderByDescending(c => c.Usable)
            .ThenByDescending(c => c.Score)
            .ToList();
    }

    /// <summary>取最佳候选（优先有录音端点配对的）。</summary>
    public static VirtualMicCandidate? LocateBest() => Locate().FirstOrDefault(c => c.Usable) ?? Locate().FirstOrDefault();

    private static (string? Id, string? Name) MatchCapture(
        string renderName,
        List<(string Id, string Name)> captures)
    {
        var normalizedRender = Normalize(renderName);

        foreach (var (id, name) in captures)
        {
            if (Normalize(name) == normalizedRender)
            {
                return (id, name);
            }
        }

        // 退一步：渲染侧名字里包含录音侧名字（"入梦扬声器 (VB-Audio Cable A)" vs "入梦麦克风 (VB-Audio Cable A)"）
        foreach (var (id, name) in captures)
        {
            var normalizedCapture = Normalize(name);
            if (normalizedCapture.Length >= 6
                && (normalizedRender.Contains(normalizedCapture) || normalizedCapture.Contains(normalizedRender)))
            {
                return (id, name);
            }
        }

        return (null, null);
    }

    /// <summary>去掉方向词和标点，用于把"扬声器/麦克风""输入/输出"这类差异抹平。</summary>
    private static string Normalize(string name)
    {
        var text = name.ToLowerInvariant();
        foreach (var token in new[] { "扬声器", "麦克风", "输入", "输出", "speaker", "speakers", "mic", "microphone", "input", "output", "(", ")", "（", "）", "-", "·" })
        {
            text = text.Replace(token, string.Empty);
        }

        return text.Replace(" ", string.Empty).Trim();
    }
}
