namespace EchoLink.Core;

/// <summary>
/// 功能开关（两端保持一致）。
///
/// 上行麦克风目前问题较多：虚拟声卡依赖内核驱动、AEC 未实现、半双工压低体验差。
/// 先整体关闭——界面不显示、引擎不启动。代码保留，修好后再打开。
/// </summary>
public static class FeatureFlags
{
    /// <summary>上行麦克风（手机 → PC）。暂时关闭。</summary>
    public const bool MicUplink = false;
}
