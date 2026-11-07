using System.Runtime.InteropServices;

namespace EchoLink.Core.Wasapi;

// WASAPI COM interop。刻意只定义本工程需要的部分，并把所有 [ComImport] 接口写成
// “扁平 vtable 顺序”，不依赖接口继承的编排规则——接口 IID 一旦给对，
// vtable 顺序就是唯一约束，扁平写法最容易核对。
//
// 全部方法用 [PreserveSig] 返回 HRESULT，因为本工程必须能读到具体错误码
// （例如 IAudioClient3 + LOOPBACK 的 0x88890021 AUDCLNT_E_INVALID_STREAM_FLAG）。

internal enum EDataFlow
{
    Render = 0,
    Capture = 1,
    All = 2,
}

internal enum ERole
{
    Console = 0,
    Multimedia = 1,
    Communications = 2,
}

[Flags]
internal enum AudioClientStreamFlags : uint
{
    None = 0,
    CrossProcess = 0x00010000,
    Loopback = 0x00020000,
    EventCallback = 0x00040000,
    NoPersist = 0x00080000,
    RateAdjust = 0x00100000,
    AutoConvertPcm = 0x80000000,
    SrcDefaultQuality = 0x08000000,
}

internal enum AudioClientShareMode : uint
{
    Shared = 0,
    Exclusive = 1,
}

internal enum AudioStreamCategory : uint
{
    Other = 0,
    ForegroundOnlyMedia = 1,
    BackgroundCapableMedia = 2,
    Communications = 3,
    Alerts = 4,
    SoundEffects = 5,
    GameEffects = 6,
    GameMedia = 7,
    GameChat = 8,
    Speech = 9,
    Movie = 10,
    Media = 11,
}

[Flags]
internal enum AudioClientStreamOptions : uint
{
    None = 0,
    Raw = 0x1,
    MatchFormat = 0x2,
}

[StructLayout(LayoutKind.Sequential)]
internal struct AudioClientProperties
{
    public int CbSize;
    public int BIsOffload;
    public AudioStreamCategory ECategory;
    public AudioClientStreamOptions Options;
}

internal static class AudioClientFlags
{
    public const uint BufferFlagsSilent = 0x2;
    public const uint BufferFlagsDataDiscontinuity = 0x1;
    public const uint BufferFlagsTimestampError = 0x4;

    public const uint ClsCtxAll = 23;

    public static readonly Guid ClsidMmDeviceEnumerator = new("BCDE0395-E52F-467C-8E3D-C4579291692E");
    public static readonly Guid IidIMMDevice = new("D666063F-1587-4E43-81F1-B948E807363F");
    public static readonly Guid IidIMMDeviceEnumerator = new("A95664D2-9614-4F35-A746-DE8DB63617E6");
    public static readonly Guid IidIAudioClient = new("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
    public static readonly Guid IidIAudioClient3 = new("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42");
    public static readonly Guid IidIAudioCaptureClient = new("C8ADBD64-E71E-48A0-A4DE-185C395CD317");
    public static readonly Guid IidIAudioRenderClient = new("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");
    public static readonly Guid IidIAudioClock = new("CD63314F-3FBA-4A1F-812E-92897B32664A");

    /// <summary>把常见 AUDCLNT_* HRESULT 翻译成可读名字，便于日志定位。</summary>
    public static string Describe(int hresult) => unchecked((uint)hresult) switch
    {
        0 => "S_OK",
        0x80070005 => "E_ACCESSDENIED",
        0x88890001 => "AUDCLNT_E_NOT_INITIALIZED",
        0x88890002 => "AUDCLNT_E_ALREADY_INITIALIZED",
        0x88890003 => "AUDCLNT_E_WRONG_ENDPOINT_TYPE",
        0x88890004 => "AUDCLNT_E_DEVICE_INVALIDATED",
        0x88890005 => "AUDCLNT_E_NOT_STOPPED",
        0x88890006 => "AUDCLNT_E_BUFFER_TOO_LARGE",
        0x88890007 => "AUDCLNT_E_OUT_OF_ORDER",
        0x88890008 => "AUDCLNT_E_UNSUPPORTED_FORMAT",
        0x88890009 => "AUDCLNT_E_INVALID_SIZE",
        0x8889000A => "AUDCLNT_E_DEVICE_IN_USE",
        0x8889000E => "AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED",
        0x8889000F => "AUDCLNT_E_ENDPOINT_CREATE_FAILED",
        0x88890010 => "AUDCLNT_E_SERVICE_NOT_RUNNING",
        0x88890011 => "AUDCLNT_E_EVENTHANDLE_NOT_EXPECTED",
        0x88890019 => "AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED",
        0x88890020 => "AUDCLNT_E_INVALID_DEVICE_PERIOD",
        0x88890021 => "AUDCLNT_E_INVALID_STREAM_FLAG",
        0x88890022 => "AUDCLNT_E_ENDPOINT_OFFLOAD_NOT_CAPABLE",
        0x88890026 => "AUDCLNT_E_RESOURCES_INVALIDATED",
        0x88890028 => "AUDCLNT_E_ENGINE_PERIODICITY_LOCKED",
        0x88890029 => "AUDCLNT_E_ENGINE_FORMAT_LOCKED",
        0x80070057 => "E_INVALIDARG",
        0x80004005 => "E_FAIL",
        0x8007007E => "E_MODNOTFOUND",
        var other => $"0x{other:X8}",
    };

    public static string FormatHResult(int hresult) => $"0x{unchecked((uint)hresult):X8} ({Describe(hresult)})";
}

[ComImport]
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDeviceEnumerator
{
    [PreserveSig]
    int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IntPtr devices);

    [PreserveSig]
    int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice device);

    [PreserveSig]
    int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);

    [PreserveSig]
    int RegisterEndpointNotificationCallback(IntPtr client);

    [PreserveSig]
    int UnregisterEndpointNotificationCallback(IntPtr client);
}

[ComImport]
[Guid("D666063F-1587-4E43-81F1-B948E807363F")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IMMDevice
{
    [PreserveSig]
    int Activate(ref Guid iid, uint clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object? activated);

    [PreserveSig]
    int OpenPropertyStore(uint storageAccess, out IntPtr properties);

    [PreserveSig]
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);

    [PreserveSig]
    int GetState(out uint state);
}

/// <summary>IAudioClient 基础接口（13 个方法）。</summary>
[ComImport]
[Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioClient
{
    [PreserveSig]
    int Initialize(
        AudioClientShareMode shareMode,
        AudioClientStreamFlags streamFlags,
        long hnsBufferDuration,
        long hnsPeriodicity,
        IntPtr format,
        IntPtr audioSessionGuid);

    [PreserveSig]
    int GetBufferSize(out uint bufferFrames);

    [PreserveSig]
    int GetStreamLatency(out long latency);

    [PreserveSig]
    int GetCurrentPadding(out uint paddingFrames);

    [PreserveSig]
    int IsFormatSupported(AudioClientShareMode shareMode, IntPtr format, out IntPtr closestMatch);

    [PreserveSig]
    int GetMixFormat(out IntPtr deviceFormat);

    [PreserveSig]
    int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);

    [PreserveSig]
    int Start();

    [PreserveSig]
    int Stop();

    [PreserveSig]
    int Reset();

    [PreserveSig]
    int SetEventHandle(IntPtr eventHandle);

    [PreserveSig]
    int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object? service);
}

/// <summary>
/// IAudioClient3：扁平声明 IAudioClient -> IAudioClient2 -> IAudioClient3 的全部 21 个方法
/// （4 + 3 + 14 中的实际顺序），IID 用 IAudioClient3 的，避免接口继承的编排歧义。
/// </summary>
[ComImport]
[Guid("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioClient3
{
    // ---- IAudioClient ----
    [PreserveSig]
    int Initialize(
        AudioClientShareMode shareMode,
        AudioClientStreamFlags streamFlags,
        long hnsBufferDuration,
        long hnsPeriodicity,
        IntPtr format,
        IntPtr audioSessionGuid);

    [PreserveSig]
    int GetBufferSize(out uint bufferFrames);

    [PreserveSig]
    int GetStreamLatency(out long latency);

    [PreserveSig]
    int GetCurrentPadding(out uint paddingFrames);

    [PreserveSig]
    int IsFormatSupported(AudioClientShareMode shareMode, IntPtr format, out IntPtr closestMatch);

    [PreserveSig]
    int GetMixFormat(out IntPtr deviceFormat);

    [PreserveSig]
    int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);

    [PreserveSig]
    int Start();

    [PreserveSig]
    int Stop();

    [PreserveSig]
    int Reset();

    [PreserveSig]
    int SetEventHandle(IntPtr eventHandle);

    [PreserveSig]
    int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object? service);

    // ---- IAudioClient2 ----
    [PreserveSig]
    int IsOffloadCapable(AudioStreamCategory category, out int offloadCapable);

    [PreserveSig]
    int SetClientProperties(ref AudioClientProperties properties);

    [PreserveSig]
    int GetBufferSizeLimits(IntPtr format, int eventDriven, out long minDuration, out long maxDuration);

    // ---- IAudioClient3 ----
    [PreserveSig]
    int GetSharedModeEnginePeriod(
        IntPtr format,
        out uint defaultPeriodInFrames,
        out uint fundamentalPeriodInFrames,
        out uint minPeriodInFrames,
        out uint maxPeriodInFrames);

    [PreserveSig]
    int GetCurrentSharedModeEnginePeriod(out IntPtr format, out uint currentPeriodInFrames);

    [PreserveSig]
    int InitializeSharedAudioStream(
        AudioClientStreamFlags streamFlags,
        uint periodInFrames,
        IntPtr format,
        IntPtr audioSessionGuid);
}

[ComImport]
[Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioCaptureClient
{
    [PreserveSig]
    int GetBuffer(
        out IntPtr data,
        out uint framesAvailable,
        out uint flags,
        out ulong devicePosition,
        out ulong qpcPosition);

    [PreserveSig]
    int ReleaseBuffer(uint framesRead);

    [PreserveSig]
    int GetNextPacketSize(out uint packetFrames);
}

[ComImport]
[Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioRenderClient
{
    [PreserveSig]
    int GetBuffer(uint framesRequested, out IntPtr data);

    [PreserveSig]
    int ReleaseBuffer(uint framesWritten, uint flags);
}

[ComImport]
[Guid("CD63314F-3FBA-4A1F-812E-92897B32664A")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAudioClock
{
    [PreserveSig]
    int GetFrequency(out ulong frequency);

    [PreserveSig]
    int GetPosition(out ulong position, out ulong qpcPosition);
}
