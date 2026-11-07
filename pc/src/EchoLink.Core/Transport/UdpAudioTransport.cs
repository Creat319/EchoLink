using System.Net;
using System.Net.Sockets;
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
                if (received < PhoneReport.Size)
                {
                    continue;
                }

                if (PhoneReport.TryParse(_receiveBuffer.AsSpan(0, received), DateTimeOffset.Now, out var report)
                    && endpoint is IPEndPoint source)
                {
                    ReportReceived?.Invoke(report, source);
                }
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
