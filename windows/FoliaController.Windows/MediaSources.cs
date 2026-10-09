using System.Diagnostics;
using Windows.Media.Control;

namespace FoliaController;

/// <summary>What the chosen player reports right now.</summary>
internal sealed record MediaSnapshot(
    string AppId,
    string AppName,
    string Title,
    string Artist,
    string Album,
    long DurationMs,
    long PositionMs,
    long SampledAt,          // Stopwatch timestamp when PositionMs was true
    bool Playing,
    double Rate,
    bool CanSeek,
    int CoverVersion)        // bumps whenever the player may have new artwork
{
    public long LivePositionMs()
    {
        var position = PositionMs;
        if (Playing)
        {
            position += (long)((Stopwatch.GetTimestamp() - SampledAt) * 1000.0 / Stopwatch.Frequency * Rate);
        }
        if (DurationMs > 0) position = Math.Min(position, DurationMs);
        return Math.Max(0, position);
    }
}

internal interface IMediaSource
{
    /// <summary>Raised on any change, and about once a second regardless (null = nothing playing).</summary>
    event Action<MediaSnapshot?>? Changed;
    Task StartAsync();
    Task<bool> ControlAsync(string action, long positionMs);
    Task<(byte[] Data, string Mime)?> ReadCoverAsync();
    /// <summary>Best-effort: bring the playing app's window to the front.</summary>
    void FocusPlayer();
}

/// <summary>
/// Reads Windows' System Media Transport Controls — the media sessions Spotify, browsers
/// (YouTube Music in Chrome / Edge) and most players publish for the volume flyout.
/// All session work happens on one refresh loop, so nothing here races.
/// </summary>
internal sealed class SmtcSource : IMediaSource, IDisposable
{
    public event Action<MediaSnapshot?>? Changed;

    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private readonly List<GlobalSystemMediaTransportControlsSession> _subscribed = new();
    private GlobalSystemMediaTransportControlsSession? _current;
    private string? _currentId;
    private long _currentStartTicks;
    private int _coverVersion;
    private int _dirty;
    private int _running;
    private int _sessionsDirty = 1;
    private System.Threading.Timer? _poll;

    public async Task StartAsync()
    {
        _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        _manager.SessionsChanged += (_, _) =>
        {
            Interlocked.Exchange(ref _sessionsDirty, 1);
            Queue();
        };
        _manager.CurrentSessionChanged += (_, _) => Queue();
        // Some players never raise timeline events; a cheap poll also drives drift checks.
        _poll = new System.Threading.Timer(_ => Queue(), null, 1000, 1000);
        Queue();
    }

    public void Dispose()
    {
        _poll?.Dispose();
        foreach (var session in _subscribed) Unsubscribe(session);
        _subscribed.Clear();
    }

    private void Queue()
    {
        Interlocked.Exchange(ref _dirty, 1);
        if (Interlocked.CompareExchange(ref _running, 1, 0) == 0) _ = Task.Run(RunLoopAsync);
    }

    private async Task RunLoopAsync()
    {
        try
        {
            while (Interlocked.Exchange(ref _dirty, 0) == 1)
            {
                await Task.Delay(30); // coalesce bursts of events
                await RefreshAsync();
            }
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
        finally
        {
            Volatile.Write(ref _running, 0);
            if (Volatile.Read(ref _dirty) == 1) Queue();
        }
    }

    private void OnMediaProperties(GlobalSystemMediaTransportControlsSession s, MediaPropertiesChangedEventArgs e)
    {
        Interlocked.Increment(ref _coverVersion);
        Queue();
    }

    private void OnPlayback(GlobalSystemMediaTransportControlsSession s, PlaybackInfoChangedEventArgs e) => Queue();
    private void OnTimeline(GlobalSystemMediaTransportControlsSession s, TimelinePropertiesChangedEventArgs e) => Queue();

    private void Subscribe(GlobalSystemMediaTransportControlsSession session)
    {
        session.MediaPropertiesChanged += OnMediaProperties;
        session.PlaybackInfoChanged += OnPlayback;
        session.TimelinePropertiesChanged += OnTimeline;
    }

    private void Unsubscribe(GlobalSystemMediaTransportControlsSession session)
    {
        try
        {
            session.MediaPropertiesChanged -= OnMediaProperties;
            session.PlaybackInfoChanged -= OnPlayback;
            session.TimelinePropertiesChanged -= OnTimeline;
        }
        catch (Exception)
        {
            // The session is already gone.
        }
    }

    private async Task RefreshAsync()
    {
        var manager = _manager;
        if (manager == null) return;

        if (Interlocked.Exchange(ref _sessionsDirty, 0) == 1)
        {
            foreach (var old in _subscribed) Unsubscribe(old);
            _subscribed.Clear();
            foreach (var session in manager.GetSessions())
            {
                Subscribe(session);
                _subscribed.Add(session);
            }
        }

        // The playing session wins; otherwise keep the one we had; otherwise Windows' pick.
        GlobalSystemMediaTransportControlsSession? playing = null, kept = null;
        foreach (var session in _subscribed)
        {
            var status = SafeStatus(session);
            if (playing == null && status == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing) playing = session;
            if (kept == null && _currentId != null && SafeId(session) == _currentId) kept = session;
        }
        GlobalSystemMediaTransportControlsSession? systemPick = null;
        try { systemPick = manager.GetCurrentSession(); } catch (Exception) { }
        var chosen = playing ?? kept ?? systemPick ?? _subscribed.FirstOrDefault();

        if (chosen == null)
        {
            _current = null;
            _currentId = null;
            Changed?.Invoke(null);
            return;
        }

        var id = SafeId(chosen);
        if (id != _currentId) Interlocked.Increment(ref _coverVersion);
        _current = chosen;
        _currentId = id;

        string title = "", artist = "", album = "";
        try
        {
            var props = await chosen.TryGetMediaPropertiesAsync();
            if (props != null)
            {
                title = props.Title ?? "";
                artist = string.IsNullOrWhiteSpace(props.Artist) ? props.AlbumArtist ?? "" : props.Artist;
                album = props.AlbumTitle ?? "";
            }
        }
        catch (Exception)
        {
            // Some apps throw while switching tracks; the next poll picks it up.
        }

        var playback = chosen.GetPlaybackInfo();
        var isPlaying = playback?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        var rate = playback?.PlaybackRate ?? 1.0;
        if (rate <= 0 || double.IsNaN(rate)) rate = 1.0;
        var canSeek = playback?.Controls?.IsPlaybackPositionEnabled ?? false;

        long durationMs = 0, positionMs = 0;
        try
        {
            var timeline = chosen.GetTimelineProperties();
            _currentStartTicks = timeline.StartTime.Ticks;
            durationMs = (long)Math.Max(0, (timeline.EndTime - timeline.StartTime).TotalMilliseconds);
            positionMs = (long)Math.Max(0, (timeline.Position - timeline.StartTime).TotalMilliseconds);
            // The position is as of LastUpdatedTime; bring it up to now while playing.
            if (isPlaying && timeline.LastUpdatedTime.Year > 2000)
            {
                var elapsed = DateTimeOffset.Now - timeline.LastUpdatedTime;
                if (elapsed > TimeSpan.Zero && elapsed < TimeSpan.FromHours(6))
                {
                    positionMs += (long)(elapsed.TotalMilliseconds * rate);
                }
            }
            if (durationMs > 0) positionMs = Math.Min(positionMs, durationMs);
        }
        catch (Exception)
        {
            // No timeline from this app.
        }

        Changed?.Invoke(new MediaSnapshot(
            id, FriendlyAppName(id), title.Trim(), artist.Trim(), album.Trim(),
            durationMs, positionMs, Stopwatch.GetTimestamp(), isPlaying, rate,
            canSeek && durationMs > 0, Volatile.Read(ref _coverVersion)));
    }

    public async Task<bool> ControlAsync(string action, long positionMs)
    {
        var session = _current;
        if (session == null) return false;
        var ok = false;
        try
        {
            switch (action)
            {
                case "toggle":
                    ok = await session.TryTogglePlayPauseAsync();
                    if (!ok)
                    {
                        var playing = SafeStatus(session) == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
                        ok = playing ? await session.TryPauseAsync() : await session.TryPlayAsync();
                    }
                    break;
                case "play":
                    ok = await session.TryPlayAsync();
                    break;
                case "pause":
                    ok = await session.TryPauseAsync();
                    break;
                case "next":
                    ok = await session.TrySkipNextAsync();
                    break;
                case "prev":
                    ok = await session.TrySkipPreviousAsync();
                    break;
                case "seek":
                    ok = await session.TryChangePlaybackPositionAsync(_currentStartTicks + Math.Max(0, positionMs) * TimeSpan.TicksPerMillisecond);
                    break;
            }
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
        Queue();
        return ok;
    }

    public async Task<(byte[] Data, string Mime)?> ReadCoverAsync()
    {
        var session = _current;
        if (session == null) return null;
        try
        {
            var props = await session.TryGetMediaPropertiesAsync();
            var thumbnail = props?.Thumbnail;
            if (thumbnail == null) return null;
            using var stream = await thumbnail.OpenReadAsync();
            using var input = stream.AsStreamForRead();
            using var buffer = new MemoryStream();
            await input.CopyToAsync(buffer);
            var data = buffer.ToArray();
            if (data.Length == 0) return null;
            return (data, ImageMime(data, stream.ContentType));
        }
        catch (Exception)
        {
            return null;
        }
    }

    public void FocusPlayer() => WindowFocus.BringAppToFront(_currentId);

    private static GlobalSystemMediaTransportControlsSessionPlaybackStatus? SafeStatus(GlobalSystemMediaTransportControlsSession session)
    {
        try { return session.GetPlaybackInfo()?.PlaybackStatus; } catch (Exception) { return null; }
    }

    private static string SafeId(GlobalSystemMediaTransportControlsSession session)
    {
        try { return session.SourceAppUserModelId ?? ""; } catch (Exception) { return ""; }
    }

    internal static string ImageMime(byte[] data, string? reported)
    {
        if (data.Length > 3 && data[0] == 0xFF && data[1] == 0xD8) return "image/jpeg";
        if (data.Length > 8 && data[0] == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) return "image/png";
        if (data.Length > 12 && data[0] == 0x52 && data[1] == 0x49 && data[8] == 0x57 && data[9] == 0x45) return "image/webp";
        if (data.Length > 3 && data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46) return "image/gif";
        if (data.Length > 2 && data[0] == 0x42 && data[1] == 0x4D) return "image/bmp";
        return string.IsNullOrWhiteSpace(reported) ? "image/jpeg" : reported!;
    }

    /// <summary>"Spotify.exe" → Spotify, "Chrome" → Chrome, "SpotifyAB.SpotifyMusic_…!Spotify" → Spotify.</summary>
    internal static string FriendlyAppName(string id)
    {
        if (string.IsNullOrWhiteSpace(id)) return "";
        var lower = id.ToLowerInvariant();
        if (lower.Contains("spotify")) return "Spotify";
        if (lower.Contains("chrome")) return "Chrome";
        if (lower.Contains("msedge") || lower.Contains("microsoftedge")) return "Edge";
        if (lower.Contains("firefox")) return "Firefox";
        if (lower.Contains("ytmdesktop") || lower.Contains("youtube")) return "YouTube Music";
        if (lower.Contains("applemusic") || lower.Contains("appleinc.")) return "Apple Music";
        if (lower.Contains("zunemusic") || lower.Contains("mediaplayer")) return "媒體播放器";
        if (lower.Contains("cloudmusic")) return "網易雲音樂";
        if (lower.Contains("qqmusic")) return "QQ 音樂";
        if (lower.Contains("kkbox")) return "KKBOX";
        var name = id;
        var bang = name.LastIndexOf('!');
        if (bang >= 0 && bang < name.Length - 1) name = name[(bang + 1)..];
        name = Path.GetFileName(name);
        if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) name = name[..^4];
        return name;
    }
}

/// <summary>Stand-in player for the CI smoke test (no media sessions on a build server).</summary>
internal sealed class FakeSource : IMediaSource
{
    public event Action<MediaSnapshot?>? Changed;
    public List<string> Commands { get; } = new();

    private readonly object _gate = new();
    private bool _playing = true;
    private long _position = 30_000;
    private long _sampledAt = Stopwatch.GetTimestamp();
    private System.Threading.Timer? _timer;

    // 1×1 PNG.
    private static readonly byte[] Png = Convert.FromBase64String(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    public Task StartAsync()
    {
        lock (_gate) _sampledAt = Stopwatch.GetTimestamp();
        _timer = new System.Threading.Timer(_ => Emit(), null, 200, 1000);
        return Task.CompletedTask;
    }

    private void Emit()
    {
        MediaSnapshot snapshot;
        lock (_gate)
        {
            snapshot = new MediaSnapshot("Spotify.exe", "Spotify", "晴天", "周杰倫", "葉惠美", 269_000,
                _position, _sampledAt, _playing, 1.0, true, 1);
        }
        Changed?.Invoke(snapshot);
    }

    public Task<bool> ControlAsync(string action, long positionMs)
    {
        lock (_gate)
        {
            Commands.Add(action == "seek" ? $"seek:{positionMs}" : action);
            var now = Stopwatch.GetTimestamp();
            if (_playing) _position += (now - _sampledAt) * 1000 / Stopwatch.Frequency;
            _sampledAt = now;
            if (action == "toggle") _playing = !_playing;
            if (action == "seek") _position = positionMs;
        }
        Emit();
        return Task.FromResult(true);
    }

    public Task<(byte[] Data, string Mime)?> ReadCoverAsync() =>
        Task.FromResult<(byte[] Data, string Mime)?>((Png, "image/png"));

    public void FocusPlayer() => Commands.Add("focus");
}
