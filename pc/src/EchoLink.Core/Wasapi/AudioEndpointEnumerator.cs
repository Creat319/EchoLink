using System.Runtime.InteropServices;

namespace EchoLink.Core.Wasapi;

/// <summary>一个音频端点（渲染或录音）的枚举结果。</summary>
public sealed record AudioEndpoint(string Id, string FriendlyName, bool IsDefault);

/// <summary>
/// 音频端点枚举：**用我们自己的 COM 封装**，不走 NAudio。
///
/// 起因：有用户的机器上 `new MMDeviceEnumerator()` 直接抛
/// "Unable to cast object of type 'System.__ComObject' to type
/// 'NAudio.CoreAudioApi.Interfaces.IMMDeviceEnumeratorComObject'"，导致
/// 「枚举音频端点失败」、端点列表为空、探测到 0 个端点，最后只能退到默认 10 ms 周期，
/// 连虚拟声卡检测也一并失效。
///
/// 而本项目自己的 COM 封装（LoopbackCapture / KeepAliveRenderer / MicUplinkPlayer 都在用）
/// 在同一台机器上完全正常，所以这里改成同一套：
/// CoCreateInstance(MMDeviceEnumerator) → EnumAudioEndpoints → GetId / OpenPropertyStore。
/// </summary>
public static class AudioEndpointEnumerator
{
    // PKEY_Device_FriendlyName = {A45C254E-DF1C-4EFD-8020-67D146A850E0}, 14
    private static readonly PropertyKey FriendlyNameKey = new()
    {
        FormatId = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"),
        PropertyId = 14,
    };

    private const uint StgmRead = 0;
    private const uint DevStateActive = 0x1;

    public static IReadOnlyList<AudioEndpoint> ListRender() => ListActive(EDataFlow.Render);

    public static IReadOnlyList<AudioEndpoint> ListCapture() => ListActive(EDataFlow.Capture);

    /// <summary>
    /// 枚举当前活动的端点。任何失败都返回空列表、**不抛异常**——
    /// 设备枚举失败不该让主界面或麦克风配置崩掉，调用方都有兜底路径。
    /// </summary>
    private static IReadOnlyList<AudioEndpoint> ListActive(EDataFlow flow)
    {
        var results = new List<AudioEndpoint>();
        var coInitialized = false;

        try
        {
            var hrCo = Com.CoInitializeEx(IntPtr.Zero, Com.CoinitMultithreaded);
            coInitialized = hrCo == 0;

            var enumerator = Com.CreateDeviceEnumerator();
            string? defaultId = null;
            try
            {
                if (enumerator.GetDefaultAudioEndpoint(flow, ERole.Multimedia, out var def) == 0 &&
                    def is not null)
                {
                    if (def.GetId(out var id) == 0)
                    {
                        defaultId = id;
                    }

                    Release(def);
                }
            }
            catch
            {
                // 没有默认设备不算错误
            }

            if (enumerator.EnumAudioEndpoints(flow, DevStateActive, out var collectionPtr) != 0 ||
                collectionPtr == IntPtr.Zero)
            {
                return results;
            }

            var collection = (IMMDeviceCollection)Marshal.GetObjectForIUnknown(collectionPtr);
            try
            {
                if (collection.GetCount(out var count) != 0)
                {
                    return results;
                }

                for (uint i = 0; i < count; i++)
                {
                    if (collection.Item(i, out var device) != 0 || device is null)
                    {
                        continue;
                    }

                    try
                    {
                        if (device.GetId(out var id) != 0 || string.IsNullOrEmpty(id))
                        {
                            continue;
                        }

                        var name = ReadFriendlyName(device) ?? "未命名端点";
                        results.Add(new AudioEndpoint(id, name, id == defaultId));
                    }
                    finally
                    {
                        Release(device);
                    }
                }
            }
            finally
            {
                Release(collection);
            }

            Release(enumerator);
        }
        catch
        {
            // 枚举失败就用已经拿到的部分（可能是空列表）
        }
        finally
        {
            if (coInitialized)
            {
                Com.CoUninitialize();
            }
        }

        return results;
    }

    private static string? ReadFriendlyName(IMMDevice device)
    {
        if (device.OpenPropertyStore(StgmRead, out var storePtr) != 0 || storePtr == IntPtr.Zero)
        {
            return null;
        }

        var store = (IPropertyStore)Marshal.GetObjectForIUnknown(storePtr);
        try
        {
            var key = FriendlyNameKey;
            if (store.GetValue(ref key, out var value) != 0)
            {
                return null;
            }

            try
            {
                return value.AsString();
            }
            finally
            {
                value.Clear();
            }
        }
        finally
        {
            Release(store);
        }
    }

    private static void Release(object? comObject)
    {
        if (comObject is null || !Marshal.IsComObject(comObject))
        {
            return;
        }

        try
        {
            Marshal.ReleaseComObject(comObject);
        }
        catch
        {
            // 释放失败不影响结果
        }
    }
}
