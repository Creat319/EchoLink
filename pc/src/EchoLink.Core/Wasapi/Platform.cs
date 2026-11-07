using System.Runtime.InteropServices;

namespace EchoLink.Core.Wasapi;

/// <summary>把当前线程提升到 MMCSS "Pro Audio" 实时优先级，并缩短系统定时器分辨率。</summary>
internal sealed class RealtimeScope : IDisposable
{
    private readonly IntPtr _mmcssHandle;
    private readonly uint _timerPeriod;

    private RealtimeScope(IntPtr handle, uint timerPeriod)
    {
        _mmcssHandle = handle;
        _timerPeriod = timerPeriod;
    }

    public bool MmcssEnabled => _mmcssHandle != IntPtr.Zero;

    public static RealtimeScope EnterProAudio()
    {
        var handle = IntPtr.Zero;
        var taskIndex = 0u;
        try
        {
            handle = AvSetMmThreadCharacteristicsW("Pro Audio", out taskIndex);
        }
        catch (DllNotFoundException)
        {
        }
        catch (EntryPointNotFoundException)
        {
        }

        uint period = 0;
        try
        {
            period = timeBeginPeriod(1);
        }
        catch (DllNotFoundException)
        {
        }

        return new RealtimeScope(handle, period);
    }

    public void Dispose()
    {
        if (_mmcssHandle != IntPtr.Zero)
        {
            AvRevertMmThreadCharacteristics(_mmcssHandle);
        }

        if (_timerPeriod == 0)
        {
            timeEndPeriod(1);
        }
    }

    [DllImport("avrt.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr AvSetMmThreadCharacteristicsW(string taskName, out uint taskIndex);

    [DllImport("avrt.dll", SetLastError = true)]
    private static extern bool AvRevertMmThreadCharacteristics(IntPtr taskHandle);

    [DllImport("winmm.dll", EntryPoint = "timeBeginPeriod")]
    private static extern uint timeBeginPeriod(uint period);

    [DllImport("winmm.dll", EntryPoint = "timeEndPeriod")]
    private static extern uint timeEndPeriod(uint period);
}

/// <summary>Win32 自动重置事件。</summary>
internal sealed class WaitableEvent : IDisposable
{
    private const uint EventAllAccess = 0x1F0003;
    private const uint WaitObject0 = 0;
    private const uint WaitTimeout = 258;

    public WaitableEvent(string? name = null, bool manualReset = false)
    {
        Handle = CreateEventExW(IntPtr.Zero, name, manualReset ? 1u : 0u, EventAllAccess);
        if (Handle == IntPtr.Zero)
        {
            throw new InvalidOperationException("CreateEventEx failed: " + Marshal.GetLastWin32Error());
        }
    }

    public IntPtr Handle { get; private set; }

    public bool Wait(int milliseconds)
    {
        var result = WaitForSingleObject(Handle, (uint)milliseconds);
        return result is WaitObject0 or 0x80 /*WAIT_ABANDONED*/;
    }

    public void Set() => SetEvent(Handle);

    public void Dispose()
    {
        if (Handle != IntPtr.Zero)
        {
            CloseHandle(Handle);
            Handle = IntPtr.Zero;
        }
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateEventExW(IntPtr attributes, string? name, uint flags, uint desiredAccess);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetEvent(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);
}

internal static class Com
{
    public const int CoinitMultithreaded = 0x0;

    [DllImport("ole32.dll")]
    public static extern int CoInitializeEx(IntPtr reserved, int coinit);

    [DllImport("ole32.dll")]
    public static extern void CoUninitialize();

    [DllImport("ole32.dll")]
    public static extern void CoTaskMemFree(IntPtr pv);

    public static IMMDeviceEnumerator CreateDeviceEnumerator()
    {
        var type = Type.GetTypeFromCLSID(AudioClientFlags.ClsidMmDeviceEnumerator, throwOnError: true)!;
        return (IMMDeviceEnumerator)Activator.CreateInstance(type)!;
    }

    /// <summary>取渲染端点；<paramref name="deviceId"/> 为空时取系统默认输出。</summary>
    public static IMMDevice OpenRenderDevice(IMMDeviceEnumerator enumerator, string? deviceId)
    {
        int hr;
        IMMDevice? device;
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            hr = enumerator.GetDefaultAudioEndpoint(EDataFlow.Render, ERole.Multimedia, out device);
        }
        else
        {
            hr = enumerator.GetDevice(deviceId, out device);
        }

        if (hr != 0 || device is null)
        {
            throw new WasapiException($"打开渲染端点失败: {AudioClientFlags.FormatHResult(hr)}", hr);
        }

        return device;
    }

    public static object Activate(IMMDevice device, Guid iid)
    {
        var hr = device.Activate(ref iid, AudioClientFlags.ClsCtxAll, IntPtr.Zero, out var activated);
        if (hr != 0 || activated is null)
        {
            throw new WasapiException($"Activate 失败: {AudioClientFlags.FormatHResult(hr)}", hr);
        }

        return activated;
    }
}

public sealed class WasapiException : Exception
{
    public WasapiException(string message, int hresult)
        : base($"{message} [HRESULT {AudioClientFlags.FormatHResult(hresult)}]")
    {
        HResult = hresult;
    }
}
