using System.Buffers.Binary;

namespace MoonView.Services;

public static class VideoProtocol
{
    public const int HeaderBytes = 36;
    public const int MaxDatagramBytes = 1200;
    public const int MaxPayloadBytes = MaxDatagramBytes - HeaderBytes;
    public const int MaxFrameBytes = 4 * 1024 * 1024;
    public const int MaxFragments = 4096;

    public static bool TryParse(
        byte[] buffer,
        int length,
        out VideoPacket packet)
    {
        packet = default;
        if (length < HeaderBytes || length > MaxDatagramBytes) return false;
        if (buffer[0] != (byte)'M' || buffer[1] != (byte)'S' ||
            buffer[2] != (byte)'V' || buffer[3] != (byte)'1')
        {
            return false;
        }
        if (buffer[4] != 1) return false;
        var headerLength = BinaryPrimitives.ReadUInt16BigEndian(buffer.AsSpan(6, 2));
        if (headerLength != HeaderBytes) return false;

        var session = BinaryPrimitives.ReadInt64BigEndian(buffer.AsSpan(8, 8));
        var frameId = BinaryPrimitives.ReadInt32BigEndian(buffer.AsSpan(16, 4));
        var index = BinaryPrimitives.ReadUInt16BigEndian(buffer.AsSpan(20, 2));
        var count = BinaryPrimitives.ReadUInt16BigEndian(buffer.AsSpan(22, 2));
        var payloadLength = BinaryPrimitives.ReadUInt16BigEndian(buffer.AsSpan(24, 2));
        var timestamp = BinaryPrimitives.ReadInt64BigEndian(buffer.AsSpan(28, 8));

        if (count is 0 or > MaxFragments) return false;
        if (index >= count) return false;
        if (payloadLength > MaxPayloadBytes) return false;
        if (HeaderBytes + payloadLength > length) return false;

        packet = new VideoPacket(
            session,
            frameId,
            index,
            count,
            (buffer[5] & 0x01) != 0,
            timestamp,
            buffer.AsMemory(HeaderBytes, payloadLength));
        return true;
    }
}

public readonly record struct VideoPacket(
    long SessionId,
    int FrameId,
    int FragmentIndex,
    int FragmentCount,
    bool KeyFrame,
    long PresentationTimeUs,
    ReadOnlyMemory<byte> Payload);

public readonly record struct AssembledFrame(
    byte[] Data,
    bool KeyFrame,
    long PresentationTimeUs,
    int FrameId);

/// <summary>
/// Reassembles fragmented access units and emits them in decode order.
/// A short gap is tolerated; after ~90 ms the missing frame is skipped and a key frame
/// is requested so the decoder can recover quickly.
/// </summary>
public sealed class FrameAssembler
{
    private const long GapTimeoutMs = 90;
    private readonly Dictionary<int, PendingFrame> _pending = new();
    private readonly SortedDictionary<int, PendingFrame> _completed = new();
    private int? _lastDelivered;
    private long _lastLossMs;

    public event Action<AssembledFrame>? FrameReady;
    public event Action? LossDetected;

    public void Add(VideoPacket packet)
    {
        if (_lastDelivered is int last && IsOld(packet.FrameId, last)) return;
        if (_pending.TryGetValue(packet.FrameId, out var existing))
        {
            if (existing.Count != packet.FragmentCount) return;
        }
        else
        {
            if (_pending.Count >= 32) EvictOldestPending();
            existing = new PendingFrame(packet.FrameId, packet.FragmentCount, packet.KeyFrame, packet.PresentationTimeUs);
            _pending[packet.FrameId] = existing;
        }

        if (!existing.Add(packet.FragmentIndex, packet.Payload)) return;
        if (!existing.Complete) return;

        _pending.Remove(packet.FrameId);
        _completed[packet.FrameId] = existing;
        Drain(Environment.TickCount64);
    }

    private void Drain(long nowMs)
    {
        while (_completed.Count > 0)
        {
            if (_lastDelivered is null)
            {
                Deliver(_completed.First().Value);
                continue;
            }

            var expected = (_lastDelivered.Value + 1) & 0x7fff_ffff;
            if (_completed.TryGetValue(expected, out var next))
            {
                Deliver(next);
                continue;
            }

            var oldest = _completed.First().Value;
            if (_completed.Count < 4 && nowMs - oldest.FirstSeenMs < GapTimeoutMs) break;

            if (nowMs - _lastLossMs > 500)
            {
                _lastLossMs = nowMs;
                LossDetected?.Invoke();
            }
            Deliver(oldest);
        }
    }

    private void Deliver(PendingFrame frame)
    {
        _completed.Remove(frame.Id);
        var data = frame.Assemble();
        if (data.Length == 0 || data.Length > VideoProtocol.MaxFrameBytes) return;
        _lastDelivered = frame.Id;
        FrameReady?.Invoke(new AssembledFrame(data, frame.KeyFrame, frame.PresentationTimeUs, frame.Id));
    }

    private void EvictOldestPending()
    {
        var oldest = _pending.Values.OrderBy(f => f.FirstSeenMs).FirstOrDefault();
        if (oldest is not null) _pending.Remove(oldest.Id);
    }

    private static bool IsOld(int id, int last)
    {
        var diff = (id - last) & 0x7fff_ffff;
        return diff == 0 || diff > 0x3fff_ffff;
    }

    private sealed class PendingFrame
    {
        private readonly byte[]?[] _parts;
        private int _received;

        public PendingFrame(int id, int count, bool keyFrame, long presentationTimeUs)
        {
            Id = id;
            Count = count;
            KeyFrame = keyFrame;
            PresentationTimeUs = presentationTimeUs;
            FirstSeenMs = Environment.TickCount64;
            _parts = new byte[count][];
        }

        public int Id { get; }
        public int Count { get; }
        public bool KeyFrame { get; }
        public long PresentationTimeUs { get; }
        public long FirstSeenMs { get; }
        public bool Complete => _received == Count;

        public bool Add(int index, ReadOnlyMemory<byte> payload)
        {
            if (index < 0 || index >= _parts.Length || _parts[index] is not null) return false;
            if (payload.Length == 0) return false;
            _parts[index] = payload.ToArray();
            _received++;
            return true;
        }

        public byte[] Assemble()
        {
            var total = 0;
            foreach (var part in _parts)
            {
                if (part is null) return Array.Empty<byte>();
                total += part.Length;
            }
            if (total <= 0 || total > VideoProtocol.MaxFrameBytes) return Array.Empty<byte>();
            var data = new byte[total];
            var offset = 0;
            foreach (var part in _parts)
            {
                Buffer.BlockCopy(part!, 0, data, offset, part!.Length);
                offset += part.Length;
            }
            return data;
        }
    }
}
