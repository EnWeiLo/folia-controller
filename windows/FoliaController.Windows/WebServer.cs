using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Channels;

namespace FoliaController;

/// <summary>
/// A small HTTP/1.1 + WebSocket server on a plain socket (no admin rights or URL reservations
/// needed, even for LAN access). Serves Folia and our page, the Now Playing WebSocket Folia
/// connects to, and the control / settings endpoints.
/// </summary>
internal sealed class WebServer : IDisposable
{
    private const string WebSocketGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private readonly WebAssets _assets;
    private readonly MediaHub _hub;
    private readonly object _clientsGate = new();
    private readonly List<WsClient> _clients = new();
    private readonly List<WsClient> _audioClients = new();
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;

    public int Port { get; private set; }
    public bool Lan { get; private set; }
    public bool Running => _listener != null;

    /// <summary>How many pages listen to the audio stream (capture runs only while someone does).</summary>
    public event Action<int>? AudioListenersChanged;

    public WebServer(WebAssets assets, MediaHub hub)
    {
        _assets = assets;
        _hub = hub;
    }

    /// <summary>Listens on <paramref name="preferredPort"/>, or the next free one of the 20 after it.</summary>
    public void Start(int preferredPort, bool lan)
    {
        Stop();
        SocketException? last = null;
        for (var port = preferredPort; port < preferredPort + 20; port++)
        {
            var listener = new TcpListener(lan ? IPAddress.Any : IPAddress.Loopback, port);
            listener.Server.ExclusiveAddressUse = true;
            try
            {
                listener.Start(64);
            }
            catch (SocketException ex)
            {
                last = ex;
                continue;
            }
            _listener = listener;
            Port = port;
            Lan = lan;
            _cts = new CancellationTokenSource();
            _ = AcceptLoopAsync(listener, _cts.Token);
            return;
        }
        throw new InvalidOperationException($"No free port from {preferredPort}", last);
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;
        try { _listener?.Stop(); } catch (Exception) { }
        _listener = null;
        List<WsClient> clients;
        lock (_clientsGate)
        {
            clients = _clients.Concat(_audioClients).ToList();
            _clients.Clear();
            _audioClients.Clear();
        }
        AudioListenersChanged?.Invoke(0);
        foreach (var client in clients) client.Abort();
    }

    public void Dispose() => Stop();

    public void Broadcast(string message)
    {
        lock (_clientsGate)
        {
            foreach (var client in _clients) client.Enqueue(message);
        }
    }

    /// <summary>One audio frame to every page that listens (the newest wins if a page falls behind).</summary>
    public void BroadcastAudio(string message)
    {
        lock (_clientsGate)
        {
            foreach (var client in _audioClients) client.Enqueue(message);
        }
    }

    private async Task AcceptLoopAsync(TcpListener listener, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(ct);
            }
            catch (Exception)
            {
                if (ct.IsCancellationRequested) return;
                await Task.Delay(100, CancellationToken.None);
                continue;
            }
            _ = Task.Run(() => HandleConnectionAsync(client, ct), CancellationToken.None);
        }
    }

    // ---------- HTTP ----------

    private sealed record Request(string Method, string Path, string Query, Dictionary<string, string> Headers, byte[] Body);

    private async Task HandleConnectionAsync(TcpClient client, CancellationToken ct)
    {
        client.NoDelay = true;
        var handedOff = false;
        try
        {
            using var network = client.GetStream();
            var stream = new ConnStream(network);
            while (!ct.IsCancellationRequested)
            {
                Request? request;
                using (var idle = CancellationTokenSource.CreateLinkedTokenSource(ct))
                {
                    idle.CancelAfter(TimeSpan.FromSeconds(75));
                    request = await ReadRequestAsync(stream, idle.Token);
                }
                if (request == null) return;

                if (!IsAllowedHost(request.Headers.GetValueOrDefault("Host")))
                {
                    await WriteResponseAsync(stream, request, 403, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("Forbidden host"), false, ct);
                    return;
                }

                if (request.Headers.TryGetValue("Upgrade", out var upgrade) &&
                    upgrade.Equals("websocket", StringComparison.OrdinalIgnoreCase))
                {
                    handedOff = true;
                    await HandleWebSocketAsync(client, stream, request, ct);
                    return;
                }

                var keepAlive = !request.Headers.TryGetValue("Connection", out var connection) ||
                                !connection.Contains("close", StringComparison.OrdinalIgnoreCase);
                var (status, type, body, cache) = await RouteAsync(request);
                await WriteResponseAsync(stream, request, status, type, body, keepAlive, ct, cache);
                if (!keepAlive) return;
            }
        }
        catch (Exception)
        {
            // Client went away mid-request.
        }
        finally
        {
            if (!handedOff) client.Dispose();
        }
    }

    private static async Task<Request?> ReadRequestAsync(ConnStream stream, CancellationToken ct)
    {
        string? line;
        do
        {
            line = await stream.ReadLineAsync(ct);
            if (line == null) return null;
        } while (line.Length == 0);

        var parts = line.Split(' ');
        if (parts.Length < 3) return null;
        var target = parts[1];
        var queryIndex = target.IndexOf('?');
        var rawPath = queryIndex >= 0 ? target[..queryIndex] : target;
        var query = queryIndex >= 0 ? target[(queryIndex + 1)..] : "";
        string path;
        try { path = Uri.UnescapeDataString(rawPath); } catch (Exception) { return null; }

        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < 100; i++)
        {
            var header = await stream.ReadLineAsync(ct);
            if (header == null) return null;
            if (header.Length == 0) break;
            var colon = header.IndexOf(':');
            if (colon > 0) headers[header[..colon].Trim()] = header[(colon + 1)..].Trim();
        }

        var body = Array.Empty<byte>();
        if (headers.TryGetValue("Content-Length", out var lengthText) && int.TryParse(lengthText, out var length) && length > 0)
        {
            if (length > 256 * 1024) return null;
            body = await stream.ReadExactAsync(length, ct);
        }
        return new Request(parts[0].ToUpperInvariant(), path, query, headers, body);
    }

    /// <summary>
    /// Only answer to IP addresses and localhost, never to other host names: that blocks
    /// DNS-rebinding pages from talking to this server through the browser.
    /// </summary>
    private static bool IsAllowedHost(string? host)
    {
        if (string.IsNullOrWhiteSpace(host)) return false;
        var name = host.Trim();
        if (name.StartsWith('['))
        {
            var end = name.IndexOf(']');
            return end > 0 && IPAddress.TryParse(name[1..end], out _);
        }
        var colon = name.LastIndexOf(':');
        if (colon >= 0) name = name[..colon];
        return name.Equals("localhost", StringComparison.OrdinalIgnoreCase) || IPAddress.TryParse(name, out _);
    }

    private async Task<(int Status, string Type, byte[] Body, string Cache)> RouteAsync(Request request)
    {
        const string NoCache = "no-cache";
        var path = request.Path;

        if (request.Method == "POST")
        {
            // A header a cross-site form or image can't send: other web pages open in a
            // browser on this PC can't press our buttons.
            if (!request.Headers.TryGetValue("X-FC", out var marker) || marker != "1")
            {
                return (403, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("Forbidden"), NoCache);
            }
            JsonDocument? doc = null;
            try
            {
                doc = request.Body.Length > 0 ? JsonDocument.Parse(request.Body) : JsonDocument.Parse("{}");
                if (doc.RootElement.ValueKind != JsonValueKind.Object) throw new JsonException();
            }
            catch (JsonException)
            {
                doc?.Dispose();
                return (400, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("Bad JSON"), NoCache);
            }
            using (doc)
            {
                var body = doc.RootElement;
                switch (path)
                {
                    case "/api/control":
                        var action = body.TryGetProperty("action", out var a) && a.ValueKind == JsonValueKind.String ? a.GetString() ?? "" : "";
                        long position = body.TryGetProperty("positionMs", out var p) && p.ValueKind == JsonValueKind.Number && p.TryGetInt64(out var pv) ? pv : 0;
                        if (action == "focus")
                        {
                            _hub.FocusPlayer();
                            return Json("{\"ok\":true}");
                        }
                        var ok = await _hub.ControlAsync(action, position);
                        return Json(ok ? "{\"ok\":true}" : "{\"ok\":false}");
                    case "/api/settings":
                        _hub.UpdateSettings(body);
                        return Json("{\"ok\":true}");
                    case "/api/lyrics/choose":
                        var candidate = LyricCandidate.FromJson(JsonNode.Parse(body.GetRawText()));
                        if (candidate == null) return (400, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("Bad candidate"), NoCache);
                        using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20)))
                        {
                            var chosen = await _hub.ChooseAsync(candidate, cts.Token);
                            return Json(chosen ? "{\"ok\":true}" : "{\"ok\":false}");
                        }
                    case "/api/lyrics/none":
                        _hub.ChooseNone();
                        return Json("{\"ok\":true}");
                    case "/api/lyrics/reset":
                        _hub.ResetChoice();
                        return Json("{\"ok\":true}");
                }
            }
            return (404, "text/plain; charset=utf-8", Array.Empty<byte>(), NoCache);
        }

        if (request.Method is not ("GET" or "HEAD"))
        {
            return (405, "text/plain; charset=utf-8", Array.Empty<byte>(), NoCache);
        }

        if (path is "/" or "/index.html")
        {
            var isFolia = request.Query.Split('&').Contains("obs=1");
            return (200, "text/html; charset=utf-8", isFolia ? _assets.IndexHtml : _assets.LauncherHtml, NoCache);
        }
        if (path == "/api/state") return Json(_hub.ControllerStateJson());
        if (path == "/api/lyrics/candidates")
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(25));
            try
            {
                return Json((await _hub.CandidatesAsync(cts.Token)).ToJsonString());
            }
            catch (OperationCanceledException)
            {
                return Json("{\"hasSession\":true,\"candidates\":[],\"timeout\":true}");
            }
        }
        if (path.StartsWith("/api/cover/", StringComparison.Ordinal) &&
            _hub.GetCover(path["/api/cover/".Length..]) is { } cover)
        {
            // The address is the image's fingerprint, so it never points at a different picture.
            return (200, cover.Mime, cover.Data, "private, max-age=86400, immutable");
        }
        if (path.StartsWith("/__fc/", StringComparison.Ordinal))
        {
            var name = path["/__fc/".Length..];
            var data = _assets.Web(name);
            return data != null ? (200, WebAssets.MimeType(name), data, NoCache) : NotFound();
        }
        if (path == "/favicon.ico" && _assets.AppIcon.Length > 0) return (200, "image/x-icon", _assets.AppIcon, "max-age=86400");

        var file = path.TrimStart('/');
        var fileName = file[(file.LastIndexOf('/') + 1)..];
        // Service workers are left out on purpose (see WebAssets.PrepareIndex).
        if (fileName.EndsWith("sw.js", StringComparison.Ordinal) || fileName == "registerSW.js" ||
            fileName.StartsWith("workbox-", StringComparison.Ordinal))
        {
            return NotFound();
        }
        if (!file.Contains("..") && _assets.Folia(file) is { } asset)
        {
            // Vite file names carry a content hash, so those never change.
            var cache = file.StartsWith("assets/", StringComparison.Ordinal) ? "public, max-age=31536000, immutable" : NoCache;
            return (200, WebAssets.MimeType(file), asset, cache);
        }
        return NotFound();

        static (int, string, byte[], string) Json(string json) =>
            (200, "application/json; charset=utf-8", Encoding.UTF8.GetBytes(json), "no-cache");
        static (int, string, byte[], string) NotFound() =>
            (404, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("Not found"), "no-cache");
    }

    private static async Task WriteResponseAsync(ConnStream stream, Request request, int status, string type, byte[] body,
        bool keepAlive, CancellationToken ct, string cache = "no-cache")
    {
        var reason = status switch
        {
            200 => "OK", 400 => "Bad Request", 403 => "Forbidden", 404 => "Not Found", 405 => "Method Not Allowed", _ => "OK",
        };
        var head = new StringBuilder()
            .Append("HTTP/1.1 ").Append(status).Append(' ').Append(reason).Append("\r\n")
            .Append("Content-Type: ").Append(type).Append("\r\n")
            .Append("Content-Length: ").Append(body.Length).Append("\r\n")
            .Append("Cache-Control: ").Append(cache).Append("\r\n")
            .Append("X-Content-Type-Options: nosniff\r\n")
            .Append("Referrer-Policy: no-referrer\r\n")
            .Append(keepAlive ? "Connection: keep-alive\r\n" : "Connection: close\r\n")
            .Append("\r\n");
        await stream.WriteAsync(Encoding.ASCII.GetBytes(head.ToString()), ct);
        if (request.Method != "HEAD" && body.Length > 0) await stream.WriteAsync(body, ct);
        await stream.FlushAsync(ct);
    }

    // ---------- WebSocket ----------

    private async Task HandleWebSocketAsync(TcpClient tcp, ConnStream stream, Request request, CancellationToken ct)
    {
        try
        {
            var host = request.Headers.GetValueOrDefault("Host") ?? "";
            var sameOrigin = !request.Headers.TryGetValue("Origin", out var origin) ||
                             (Uri.TryCreate(origin, UriKind.Absolute, out var originUri) &&
                              originUri.Authority.Equals(host, StringComparison.OrdinalIgnoreCase));
            var isAudio = request.Path == "/api/ws/audio";
            if ((request.Path != "/api/ws/lyric" && !isAudio) || !sameOrigin ||
                !request.Headers.TryGetValue("Sec-WebSocket-Key", out var key) || key.Length == 0)
            {
                await WriteResponseAsync(stream, request, 403, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("Forbidden"), false, ct);
                return;
            }

            var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + WebSocketGuid)));
            var handshake = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            $"Sec-WebSocket-Accept: {accept}\r\n\r\n";
            await stream.WriteAsync(Encoding.ASCII.GetBytes(handshake), ct);
            await stream.FlushAsync(ct);

            using var socket = WebSocket.CreateFromStream(stream, new WebSocketCreationOptions
            {
                IsServer = true,
                KeepAliveInterval = TimeSpan.FromSeconds(25),
            });
            var client = new WsClient(socket, isAudio ? 4 : 1024);
            var list = isAudio ? _audioClients : _clients;
            if (!isAudio) foreach (var message in _hub.SnapshotMessages()) client.Enqueue(message);
            int audioCount;
            lock (_clientsGate)
            {
                list.Add(client);
                audioCount = _audioClients.Count;
            }
            if (isAudio) AudioListenersChanged?.Invoke(audioCount);
            try
            {
                var sending = client.SendLoopAsync(ct);
                await ReceiveLoopAsync(client, ct);
                client.Complete();
                await sending;
            }
            finally
            {
                lock (_clientsGate)
                {
                    list.Remove(client);
                    audioCount = _audioClients.Count;
                }
                if (isAudio) AudioListenersChanged?.Invoke(audioCount);
            }
        }
        catch (Exception)
        {
            // Closed.
        }
        finally
        {
            tcp.Dispose();
        }
    }

    private async Task ReceiveLoopAsync(WsClient client, CancellationToken ct)
    {
        var buffer = new byte[4096];
        var socket = client.Socket;
        while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
        {
            using var message = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    try { await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None); } catch (Exception) { }
                    return;
                }
                if (message.Length + result.Count > 64 * 1024) return;
                message.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);

            if (result.MessageType != WebSocketMessageType.Text) continue;
            try
            {
                using var doc = JsonDocument.Parse(message.ToArray());
                if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                    doc.RootElement.TryGetProperty("type", out var type) && type.GetString() == "resync")
                {
                    // A page woke up from being frozen: give every page a full refresh
                    // (Folia ignores repeats of what it already has).
                    foreach (var m in _hub.SnapshotMessages()) Broadcast(m);
                }
            }
            catch (JsonException)
            {
            }
        }
    }

    private sealed class WsClient
    {
        // A frozen page (window hidden to the tray) stops reading; keep the newest messages only.
        private readonly Channel<string> _queue;

        public WebSocket Socket { get; }

        public WsClient(WebSocket socket, int capacity)
        {
            Socket = socket;
            _queue = Channel.CreateBounded<string>(new BoundedChannelOptions(capacity)
            {
                FullMode = BoundedChannelFullMode.DropOldest,
                SingleReader = true,
            });
        }

        public void Enqueue(string message) => _queue.Writer.TryWrite(message);

        public void Complete() => _queue.Writer.TryComplete();

        public void Abort()
        {
            _queue.Writer.TryComplete();
            try { Socket.Abort(); } catch (Exception) { }
        }

        public async Task SendLoopAsync(CancellationToken ct)
        {
            try
            {
                await foreach (var message in _queue.Reader.ReadAllAsync(ct))
                {
                    if (Socket.State != WebSocketState.Open) break;
                    await Socket.SendAsync(Encoding.UTF8.GetBytes(message).AsMemory(), WebSocketMessageType.Text, true, ct);
                }
            }
            catch (Exception)
            {
                // Socket closed.
            }
        }
    }
}

/// <summary>
/// The network stream with a read buffer for parsing request lines. Reads drain the buffer
/// first, so bytes that arrived right behind a WebSocket handshake are not lost; writes go
/// straight through (the WebSocket never needs to flush).
/// </summary>
internal sealed class ConnStream : Stream
{
    private readonly Stream _inner;
    private readonly byte[] _buffer = new byte[16 * 1024];
    private int _pos;
    private int _len;

    public ConnStream(Stream inner) => _inner = inner;

    public async Task<string?> ReadLineAsync(CancellationToken ct)
    {
        var line = new List<byte>(128);
        while (true)
        {
            if (_pos >= _len)
            {
                _pos = 0;
                _len = await _inner.ReadAsync(_buffer.AsMemory(), ct);
                if (_len <= 0) return null;
            }
            var b = _buffer[_pos++];
            if (b == '\n')
            {
                if (line.Count > 0 && line[^1] == '\r') line.RemoveAt(line.Count - 1);
                return Encoding.UTF8.GetString(line.ToArray());
            }
            line.Add(b);
            if (line.Count > 16 * 1024) return null;
        }
    }

    public async Task<byte[]> ReadExactAsync(int count, CancellationToken ct)
    {
        var result = new byte[count];
        var filled = 0;
        while (filled < count)
        {
            var n = await ReadAsync(result.AsMemory(filled), ct);
            if (n <= 0) throw new EndOfStreamException();
            filled += n;
        }
        return result;
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> destination, CancellationToken cancellationToken = default)
    {
        if (_pos < _len)
        {
            var n = Math.Min(destination.Length, _len - _pos);
            _buffer.AsMemory(_pos, n).CopyTo(destination);
            _pos += n;
            return n;
        }
        return await _inner.ReadAsync(destination, cancellationToken);
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
        ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (_pos < _len)
        {
            var n = Math.Min(count, _len - _pos);
            Array.Copy(_buffer, _pos, buffer, offset, n);
            _pos += n;
            return n;
        }
        return _inner.Read(buffer, offset, count);
    }

    public override ValueTask WriteAsync(ReadOnlyMemory<byte> source, CancellationToken cancellationToken = default) =>
        _inner.WriteAsync(source, cancellationToken);

    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
        _inner.WriteAsync(buffer, offset, count, cancellationToken);

    public override void Write(byte[] buffer, int offset, int count) => _inner.Write(buffer, offset, count);
    public override void Flush() => _inner.Flush();
    public override Task FlushAsync(CancellationToken cancellationToken) => _inner.FlushAsync(cancellationToken);
    public override bool CanRead => true;
    public override bool CanWrite => true;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing) _inner.Dispose();
        base.Dispose(disposing);
    }
}
