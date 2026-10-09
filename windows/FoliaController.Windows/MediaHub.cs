using System.Diagnostics;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace FoliaController;

/// <summary>
/// Follows the player, finds its lyrics and turns everything into the events Folia's
/// "Now Playing" page understands (Track, Lyric, PlayerProgress, PlayerPauseState),
/// plus ControllerState for our own control bar. Same rules as the Android app.
/// </summary>
internal sealed class MediaHub
{
    private static readonly JsonSerializerOptions JsonOptions = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    private const long DriftThresholdMs = 150;
    private const long SeekJumpMs = 1200;

    private readonly IMediaSource _source;
    private readonly AppSettings _settings;
    private readonly object _gate = new();

    private MediaSnapshot? _snap;
    private string? _trackKey;
    private LyricResult? _lyric;
    private string _lyricStatus = "";
    private int _lyricRequest;
    private CancellationTokenSource? _lyricCts;

    private byte[]? _cover;
    private string _coverMime = "image/jpeg";
    private int _coverId;
    private int _coverVersionLoaded = -1;
    private int _coverLoading;

    private long _lastSentClockMs;
    private long _lastSentAt;
    private bool _lastSentPlaying;

    /// <summary>Sends one JSON message to every connected page.</summary>
    public Action<string> Broadcast { get; set; } = _ => { };
    /// <summary>LAN status for the settings panel.</summary>
    public Func<(bool Enabled, string[] Urls)> LanInfo { get; set; } = () => (false, Array.Empty<string>());
    /// <summary>Track or playing state changed (for the tray tooltip).</summary>
    public event Action? TrackChanged;

    public MediaHub(IMediaSource source, AppSettings settings)
    {
        _source = source;
        _settings = settings;
        _source.Changed += OnSnapshot;
    }

    public MediaSnapshot? Current
    {
        get { lock (_gate) return _snap; }
    }

    // ---------- input from the player ----------

    private void OnSnapshot(MediaSnapshot? snap)
    {
        var outgoing = new List<string>();
        var trackChanged = false;
        lock (_gate)
        {
            var previous = _snap;
            if (snap == null || (snap.Title.Length == 0 && snap.Artist.Length == 0))
            {
                if (previous == null && _trackKey == null) return;
                _snap = null;
                _trackKey = null;
                _lyric = null;
                _lyricStatus = "";
                _cover = null;
                _coverVersionLoaded = -1;
                CancelLyricLookup();
                outgoing.Add(TrackEvent());
                outgoing.Add(LyricEvent());
                outgoing.Add(PauseEvent());
                outgoing.Add(ControllerStateEvent());
                trackChanged = true;
            }
            else
            {
                _snap = snap;
                var key = $"{snap.AppId}|{snap.Title}|{snap.Artist}";
                var songChanged = key != _trackKey;
                if (songChanged)
                {
                    _trackKey = key;
                    _lyric = null;
                    _cover = null;
                    _coverVersionLoaded = -1;
                    StartLyricLookup(snap, key);
                }
                else if (previous != null && previous.DurationMs == 0 && snap.DurationMs > 0 && _lyric == null)
                {
                    // Some players publish the length a moment later; it makes matching far more reliable.
                    StartLyricLookup(snap, key);
                }

                if (snap.CoverVersion != _coverVersionLoaded) LoadCover(key, snap.CoverVersion);

                var trackFieldsChanged = songChanged || previous == null ||
                                         previous.Album != snap.Album || previous.DurationMs != snap.DurationMs ||
                                         previous.AppName != snap.AppName;
                if (trackFieldsChanged) outgoing.Add(TrackEvent());
                if (songChanged) outgoing.Add(LyricEvent());

                var playingChanged = previous == null || previous.Playing != snap.Playing || songChanged;
                var clock = ClockMs();
                var expected = _lastSentClockMs + (_lastSentPlaying ? ElapsedMsSince(_lastSentAt) : 0);
                var drift = Math.Abs(clock - expected);
                if (playingChanged || drift > DriftThresholdMs)
                {
                    // Progress first: Folia's pause handler reuses the latest precise progress.
                    outgoing.Add(ProgressEvent());
                    outgoing.Add(PauseEvent());
                }
                if (trackFieldsChanged || playingChanged || drift > SeekJumpMs || previous?.CanSeek != snap.CanSeek)
                {
                    outgoing.Add(ControllerStateEvent());
                }
                trackChanged = songChanged || playingChanged;
            }
        }
        foreach (var message in outgoing) Broadcast(message);
        if (trackChanged) TrackChanged?.Invoke();
    }

    private static long ElapsedMsSince(long stopwatchTimestamp) =>
        (Stopwatch.GetTimestamp() - stopwatchTimestamp) * 1000 / Stopwatch.Frequency;

    // ---------- lyrics ----------

    private void CancelLyricLookup()
    {
        _lyricRequest++;
        _lyricCts?.Cancel();
        _lyricCts = null;
    }

    private void StartLyricLookup(MediaSnapshot snap, string key)
    {
        CancelLyricLookup();
        var request = _lyricRequest;
        var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        _lyricCts = cts;
        _lyricStatus = "正在搜尋歌詞…";
        _ = Task.Run(async () =>
        {
            LyricResult? result = null;
            try
            {
                result = await LyricRepository.FindAsync(snap.Title, snap.Artist, snap.Album, snap.DurationMs, cts.Token);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                CrashLog.Write(ex, fatal: false);
            }

            string lyricEvent, stateEvent;
            lock (_gate)
            {
                if (request != _lyricRequest || key != _trackKey) return;
                _lyric = result;
                _lyricStatus = result == null ? "找不到同步歌詞" : $"歌詞：{result.SourceLabel}";
                lyricEvent = LyricEvent();
                stateEvent = ControllerStateEvent();
            }
            Broadcast(lyricEvent);
            Broadcast(stateEvent);
        });
    }

    // ---------- cover art ----------

    private void LoadCover(string key, int version)
    {
        if (Interlocked.CompareExchange(ref _coverLoading, 1, 0) != 0) return; // the next snapshot retries
        _ = Task.Run(async () =>
        {
            try
            {
                // Artwork often lands a moment after the title; give it that moment.
                await Task.Delay(150);
                var cover = await _source.ReadCoverAsync();
                string? trackEvent = null;
                lock (_gate)
                {
                    if (key != _trackKey) return;
                    _coverVersionLoaded = version;
                    if (cover != null && (_cover == null || !_cover.AsSpan().SequenceEqual(cover.Value.Data)))
                    {
                        _cover = cover.Value.Data;
                        _coverMime = cover.Value.Mime;
                        _coverId++;
                        trackEvent = TrackEvent();
                    }
                }
                if (trackEvent != null) Broadcast(trackEvent);
            }
            catch (Exception ex)
            {
                CrashLog.Write(ex, fatal: false);
            }
            finally
            {
                Volatile.Write(ref _coverLoading, 0);
            }
        });
    }

    public (byte[] Data, string Mime)? GetCover(int id)
    {
        lock (_gate)
        {
            return _cover != null && id == _coverId ? (_cover, _coverMime) : null;
        }
    }

    // ---------- requests from pages ----------

    public Task<bool> ControlAsync(string action, long positionMs) => _source.ControlAsync(action, positionMs);

    public void FocusPlayer() => _source.FocusPlayer();

    public void UpdateSettings(JsonElement body)
    {
        var outgoing = new List<string>();
        lock (_gate)
        {
            var lyricChanged = false;
            var offsetChanged = false;
            if (body.TryGetProperty("translation", out var t) && (t.ValueKind is JsonValueKind.True or JsonValueKind.False))
            {
                lyricChanged |= _settings.Translation != t.GetBoolean();
                _settings.Translation = t.GetBoolean();
            }
            if (body.TryGetProperty("traditional", out var tr) && (tr.ValueKind is JsonValueKind.True or JsonValueKind.False))
            {
                lyricChanged |= _settings.Traditional != tr.GetBoolean();
                _settings.Traditional = tr.GetBoolean();
            }
            if (body.TryGetProperty("offsetMs", out var o) && o.ValueKind == JsonValueKind.Number && o.TryGetInt32(out var offset))
            {
                offset = Math.Clamp(offset, -5000, 5000);
                offsetChanged = _settings.OffsetMs != offset;
                _settings.OffsetMs = offset;
            }
            if (lyricChanged) outgoing.Add(LyricEvent());
            if (offsetChanged)
            {
                outgoing.Add(ProgressEvent());
                outgoing.Add(PauseEvent());
            }
            outgoing.Add(ControllerStateEvent());
        }
        _settings.Save();
        foreach (var message in outgoing) Broadcast(message);
    }

    /// <summary>Everything a freshly connected page needs, in order.</summary>
    public List<string> SnapshotMessages()
    {
        lock (_gate)
        {
            return new List<string> { TrackEvent(), LyricEvent(), ProgressEvent(), PauseEvent(), ControllerStateEvent() };
        }
    }

    public string ControllerStateJson()
    {
        lock (_gate) return ControllerStateEvent();
    }

    /// <summary>The LAN switch changed: tell every settings panel.</summary>
    public void NotifyLanChanged()
    {
        string message;
        lock (_gate) message = ControllerStateEvent();
        Broadcast(message);
    }

    // ---------- events for Folia ----------

    private long ClockMs() => _snap == null ? 0 : Math.Max(0, _snap.LivePositionMs() - _settings.OffsetMs);

    private static string Envelope(string name, JsonObject data) =>
        new JsonObject { ["event"] = name, ["data"] = data }.ToJsonString(JsonOptions);

    private string TrackEvent()
    {
        var data = new JsonObject();
        var snap = _snap;
        if (snap != null)
        {
            data["id"] = (_trackKey ?? "").GetHashCode().ToString();
            data["title"] = snap.Title.Length > 0 ? snap.Title : "Now Playing";
            data["author"] = snap.Artist;
            data["album"] = snap.Album;
            if (snap.DurationMs > 0) data["duration"] = snap.DurationMs;
            if (_cover != null) data["cover"] = $"/api/cover/{_coverId}";
        }
        return Envelope("Track", data);
    }

    private string LyricEvent()
    {
        var data = new JsonObject();
        var snap = _snap;
        var result = _lyric;
        if (snap == null || result == null)
        {
            data["hasLyric"] = false;
            return Envelope("Lyric", data);
        }
        // Any source: LRCLIB also carries Simplified lyrics for Chinese songs. Japanese (has kana)
        // and lyrics that are already Traditional come through unchanged.
        var convert = _settings.Traditional;
        string? Text(string? value) => value == null ? null : convert ? ChineseConverter.ToTraditional(value) : value;

        data["source"] = result.Source;
        data["title"] = snap.Title;
        data["author"] = snap.Artist;
        if (snap.DurationMs > 0) data["duration"] = snap.DurationMs;
        var karaoke = Text(result.Karaoke);
        var lrc = Text(result.Lrc);
        if (karaoke != null)
        {
            data["hasKaraokeLyric"] = true;
            data["karaokeLyric"] = karaoke;
        }
        if (lrc != null)
        {
            data["hasLyric"] = true;
            data["lrc"] = lrc;
        }
        var translation = _settings.Translation ? Text(result.Translation) : null;
        if (translation != null)
        {
            data["hasTranslatedLyric"] = true;
            data["translatedLyric"] = translation;
        }
        return Envelope("Lyric", data);
    }

    private string ProgressEvent()
    {
        var clock = ClockMs();
        _lastSentClockMs = clock;
        _lastSentAt = Stopwatch.GetTimestamp();
        _lastSentPlaying = _snap?.Playing ?? false;
        return Envelope("PlayerProgress", new JsonObject { ["progress"] = clock });
    }

    private string PauseEvent() =>
        Envelope("PlayerPauseState", new JsonObject
        {
            ["isPaused"] = !(_snap?.Playing ?? false),
            ["seekbarCurrentPosition"] = ClockMs() / 1000.0,
        });

    private string ControllerStateEvent()
    {
        var snap = _snap;
        var (lanEnabled, urls) = LanInfo();
        var urlArray = new JsonArray();
        foreach (var url in urls) urlArray.Add(url);
        var data = new JsonObject
        {
            ["hasSession"] = snap != null,
            ["app"] = snap?.AppName ?? "",
            ["title"] = snap?.Title ?? "",
            ["artist"] = snap?.Artist ?? "",
            ["playing"] = snap?.Playing ?? false,
            ["positionMs"] = snap?.LivePositionMs() ?? 0,
            ["durationMs"] = snap?.DurationMs ?? 0,
            ["canSeek"] = snap?.CanSeek ?? false,
            ["lyricStatus"] = _lyricStatus,
            ["settings"] = new JsonObject
            {
                ["translation"] = _settings.Translation,
                ["traditional"] = _settings.Traditional,
                ["offsetMs"] = _settings.OffsetMs,
            },
            ["lan"] = new JsonObject { ["enabled"] = lanEnabled, ["urls"] = urlArray },
        };
        return Envelope("ControllerState", data);
    }
}
