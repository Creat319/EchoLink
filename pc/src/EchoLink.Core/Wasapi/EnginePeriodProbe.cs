using System.Runtime.InteropServices;
using EchoLink.Core.Audio;

namespace EchoLink.Core.Wasapi;

/// <summary>某个端点的共享模式引擎周期（单位：音频帧）。</summary>
public sealed record EndpointPeriods(
    string DeviceId,
    string FriendlyName,
    string Format,
    int SampleRate,
    uint DefaultFrames,
    uint FundamentalFrames,
    uint MinFrames,
    uint MaxFrames,
    uint DeviceDefaultHns,
    uint DeviceMinimumHns,
    int HResult,
    string Note)
{
    public double MsPerFrame => SampleRate > 0 ? 1000.0 / SampleRate : 0;

    public double DefaultMs => DefaultFrames * MsPerFrame;

    public double MinMs => MinFrames * MsPerFrame;

    /// <summary>该端点能否比传统 10 ms 更小；true 表示值得尝试 IAudioClient3 路径。</summary>
    public bool SupportsSmallPeriod => HResult == 0 && MinFrames > 0 && MinMs < 9.9;

    public override string ToString()
        => HResult != 0
            ? $"{FriendlyName}: GetSharedModeEnginePeriod 失败 {AudioClientFlags.FormatHResult(HResult)}"
            : $"{FriendlyName}: default {DefaultFrames}f ({DefaultMs:F2} ms), min {MinFrames}f ({MinMs:F2} ms), " +
              $"fundamental {FundamentalFrames}f, max {MaxFrames}f{(SupportsSmallPeriod ? "  ← 支持小周期" : string.Empty)}";
}

/// <summary>
/// 只读探测：查询端点是否支持比 10 ms 更小的共享模式引擎周期。
/// 不改任何系统状态，也不开流，可以随时对任意端点调用。
/// </summary>
public static class EnginePeriodProbe
{
    private const int RpcEChangedMode = unchecked((int)0x80010106);

    /// <summary>
    /// 探测当前**系统默认**渲染端点。
    /// 界面上默认选的是"系统默认输出"这个伪设备（Id 为 null），
    /// 要跟真实端点比较，必须先把默认端点解析出来。
    /// </summary>
    public static (EndpointPeriods Periods, string Name)? ProbeDefaultRender()
    {
        try
        {
            using var enumerator = new NAudio.CoreAudioApi.MMDeviceEnumerator();
            var device = enumerator.GetDefaultAudioEndpoint(
                NAudio.CoreAudioApi.DataFlow.Render,
                NAudio.CoreAudioApi.Role.Multimedia);
            return (Probe(device.ID, device.FriendlyName), device.FriendlyName);
        }
        catch
        {
            return null;
        }
    }

    public static EndpointPeriods Probe(string deviceId, string friendlyName)
    {
        var coInitialized = false;
        try
        {
            var hrCo = Com.CoInitializeEx(IntPtr.Zero, Com.CoinitMultithreaded);
            // 0x80010106 = 调用线程已用别的模型初始化过，可以继续用
            coInitialized = hrCo == 0;

            var enumerator = Com.CreateDeviceEnumerator();
            var device = Com.OpenRenderDevice(enumerator, deviceId);
            var clientRaw = Com.Activate(device, AudioClientFlags.IidIAudioClient3);

            if (clientRaw is not IAudioClient3 client3)
            {
                return Fail(deviceId, friendlyName, $"端点不支持 IAudioClient3", 0);
            }

            var client = (IAudioClient)clientRaw;
            var hr = client.GetMixFormat(out var mixFormat);
            if (hr != 0 || mixFormat == IntPtr.Zero)
            {
                return Fail(deviceId, friendlyName, "GetMixFormat 失败", hr);
            }

            try
            {
                var format = DeviceFormat.FromPointer(mixFormat);

                // 经典接口的默认/最小周期（100ns 单位），作为对照
                uint deviceDefault = 0;
                uint deviceMinimum = 0;
                if (client.GetDevicePeriod(out var defaultHns, out var minimumHns) == 0)
                {
                    deviceDefault = HnsToMillis(defaultHns);
                    deviceMinimum = HnsToMillis(minimumHns);
                }

                hr = client3.GetSharedModeEnginePeriod(
                    mixFormat,
                    out var defaultFrames,
                    out var fundamentalFrames,
                    out var minFrames,
                    out var maxFrames);

                if (hr != 0)
                {
                    return Fail(deviceId, friendlyName, $"格式 {format}", hr);
                }

                var note = minFrames > 0 && minFrames < 480
                    ? "min < 10 ms，可用 InitializeSharedAudioStream 尝试"
                    : "min 等于默认周期，本端点拿不到更小周期";

                return new EndpointPeriods(
                    deviceId,
                    friendlyName,
                    format.ToString(),
                    format.SampleRate,
                    defaultFrames,
                    fundamentalFrames,
                    minFrames,
                    maxFrames,
                    deviceDefault,
                    deviceMinimum,
                    hr == 0 ? 0 : hr,
                    note);
            }
            finally
            {
                Com.CoTaskMemFree(mixFormat);
                Release(clientRaw, device, enumerator);
            }
        }
        catch (WasapiException ex)
        {
            return Fail(deviceId, friendlyName, ex.Message, ex.HResult);
        }
        catch (Exception ex)
        {
            return Fail(deviceId, friendlyName, ex.Message, unchecked((int)0x80004005));
        }
        finally
        {
            if (coInitialized)
            {
                Com.CoUninitialize();
            }
        }
    }

    private static uint HnsToMillis(long hns) => (uint)Math.Round(hns / 10_000.0);

    private static EndpointPeriods Fail(string deviceId, string friendlyName, string note, int hr)
        => new(deviceId, friendlyName, "-", 0, 0, 0, 0, 0, 0, 0, hr, note);

    private static void Release(params object[] comObjects)
    {
        foreach (var obj in comObjects)
        {
            if (obj is not null && Marshal.IsComObject(obj))
            {
                try
                {
                    Marshal.ReleaseComObject(obj);
                }
                catch
                {
                    // 释放失败不影响探测结果
                }
            }
        }
    }
}
