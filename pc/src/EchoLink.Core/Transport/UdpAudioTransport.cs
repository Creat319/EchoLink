using System.Net;
using System.Net.Sockets;
using EchoLink.Core.Audio;
using EchoLink.Core.Protocol;

namespace EchoLink.Core.Transport;

/// <summary>
/// 音频包发送 + 状态回报接收，共用一个 UDP socket。
/// PC 绑定固定端口（默认 47811），因此手机把回报发回源地址:源端口就能回到这里。
/// </summary>
public sealed class UdpAudioTransport : IDisposable
{
    private const int SioUdpConnReset = unchecked((int)0x9800000C);

    private readonly Socket _socket;
    private readonly Thread _receiveThread;
    private readonly byte[] _receiveBuffer = new byte[2048];
    private volatile bool _stop;

    private long _packetsSent;
    private long _bytesSent;
    private long _sendErrors;

    public UdpAudioTransport(int bindPort = PacketFormat.DefaultReportPort)
    {
        BindPort = bindPort;
        _socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);

        try
        {
            _socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        }
        catch (SocketException)
        {
        }

        _socket.Bind(new IPEndPoint(IPAddress.Any, bindPort));
        _socket.ReceiveBufferSize = 128 * 1024;
        _socket.SendBufferSize = 128 * 1024;

        // 关掉 SIO_UDP_CONNRESET：手机不在线时 ICMP 端口不可达会让 Receive 抛 ConnectionReset
        try
        {
            _socket.IOControl(SioUdpConnReset, new byte[] { 0, 0, 0, 0 }, null);
        }
        catch (SocketException)
        {
        }
        catch (PlatformNotSupportedException)
        {
        }

        _receiveThread = new Thread(ReceiveLoop)
        {
            IsBackground = true,
            Name = "EchoLink-ReportRx",
        };
        _receiveThread.Start();
    }

    public int BindPort { get; }

    /// <summary>当前目标（手机地址:音频端口）。null 表示还没锁定目标。</summary>
    public IPEndPoint? Remote { get; set; }

    public long PacketsSent => Interlocked.Read(ref _packetsSent);

    public long BytesSent => Interlocked.Read(ref _bytesSent);

    public long SendErrors => Interlocked.Read(ref _sendErrors);

    public event Action<PhoneReport, IPEndPoint>? ReportReceived;

    /// <summary>上行麦克风的落点（协议 v3 起：包头 stream=1 的包）。</summary>
    public IUplinkSink? UplinkSink { get; set; }

    /// <summary>最近一次收到上行音频包的对端地址，用于诊断。</summary>
    public IPEndPoint? UplinkSource { get; private set; }

    public void Send(ReadOnlySpan<byte> packet)
    {
        var remote = Remote;
        if (remote is null)
        {
            return;
        }

        try
        {
            var sent = _socket.SendTo(packet, SocketFlags.None, remote);
            Interlocked.Increment(ref _packetsSent);
            Interlocked.Add(ref _bytesSent, sent);
        }
        catch (SocketException)
        {
            Interlocked.Increment(ref _sendErrors);
        }
        catch (ObjectDisposedException)
        {
        }
    }

    private void ReceiveLoop()
    {
        var endpoint = (EndPoint)new IPEndPoint(IPAddress.Any, 0);
        while (!_stop)
        {
            try
            {
                if (!_socket.Poll(200_000, SelectMode.SelectRead))
                {
                    continue;
                }

                var received = _socket.ReceiveFrom(_receiveBuffer, ref endpoint);
                // 回报包 40 B、上行音频包 20 B 头起，统一先按最小包头判断
                if (received < PacketFormat.HeaderBytes)
                {
                    continue;
                }

                if (endpoint is not IPEndPoint source)
                {
                    continue;
                }

                // 协议 v3：同一个 socket 上还有上行音频包（stream=1），先按音频包试解析。
                // 音频包头 20 B，回报包 40 B，两者靠 magic 区分：音频包首字段是 seq，
                // 而回报包首字段是固定 magic，所以先试回报、再试音频最省。
                if (PhoneReport.TryParse(_receiveBuffer.AsSpan(0, received), DateTimeOffset.Now, out var report))
                {
                    ReportReceived?.Invoke(report, source);
                    continue;
                }

                var sink = UplinkSink;
                if (sink is null || received < PacketFormat.HeaderBytes)
                {
                    continue;
                }

                if (!AudioPacketHeader.TryRead(_receiveBuffer.AsSpan(0, received), out var header) || !header.IsUplink)
                {
                    continue;
                }

                var payloadBytes = received - PacketFormat.PayloadOffset;
                if (payloadBytes <= 0 || payloadBytes % PacketFormat.MicBytesPerFrame != 0)
                {
                    continue;
                }

                var frames = payloadBytes / PacketFormat.MicBytesPerFrame;
                UplinkSource = source;
                sink.PushMic(
                    System.Runtime.InteropServices.MemoryMarshal.Cast<byte, short>(
                        _receiveBuffer.AsSpan(PacketFormat.PayloadOffset, payloadBytes)),
                    frames);
            }
            catch (SocketException)
            {
                // 对端不可达 / 缓冲区错误，继续等下一包
            }
            catch (ObjectDisposedException)
            {
                return;
            }
        }
    }

    public void Dispose()
    {
        _stop = true;
        try
        {
            _socket.Close();
        }
        catch (ObjectDisposedException)
        {
        }

        _receiveThread.Join(TimeSpan.FromSeconds(1));
    }
}
