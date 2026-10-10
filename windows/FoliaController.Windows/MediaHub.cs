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
    private string? _lyricContainer;
    private List<LyricCandidate> _candidates = new();
    private bool _candidatesSearched;
    private bool _noLyricsChosen;
    private string _lyricStatus = "";
    private int _lyricRequest;
    private CancellationTokenSource? _lyricCts;

    private byte[]? _cover;
    private string _coverMime = "image/jpeg";
    private int _coverId;
    // The cover's address: a fingerprint of the image, so a cached copy can never be another song's
    // (a counter restarts at 1 every launch, while the window's browser cache survives).
    private string _coverKey = "";
    private int _coverVersionLoaded = -1;
    private int _coverLoading;
    private int _coverRequests;
    // The song before's artwork: players (Spotify especially) often keep it on the new song for a
    // moment, so a cover equal to it is treated as not-yet-updated.
    private byte[]? _previousSongCover;
    private string _previousAlbum = "";
    private CoverPalette _palette = CoverPalette.Neutral;
    private LyricDoc? _displayDoc;

    private long _lastSentClockMs;
    private long _lastSentAt;
    private bool _lastSentPlaying;

    /// <summary>Sends one JSON message to every connected page.</summary>
    public Action<string> Broadcast { get; set; } = _ => { };
    /// <summary>LAN status for the settings panel.</summary>
    public Func<(bool Enabled, string[] Urls)> LanInfo { get; set; } = () => (false, Array.Empty<string>());
    /// <summary>Track or playing state changed (for the tray tooltip).</summary>
    public event Action? TrackChanged;
    /// <summary>Anything the desktop lyrics show changed: song, lyrics, cover, play / pause, a jump in position.</summary>
    public event Action? DisplayChanged;
    /// <summary>A shared setting changed (e.g. 音樂律動 on / off).</summary>
    public event Action? SettingsChanged;

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
                _lyricContainer = null;
                _candidates = new();
                _candidatesSearched = false;
                _noLyricsChosen = false;
                _lyricStatus = "";
                _cover = null;
                _palette = CoverPalette.Neutral;
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
                    if (_cover != null) _previousSongCover = _cover;
                    _previousAlbum = previous?.Album ?? "";
                    _trackKey = key;
                    _lyric = null;
                    _lyricContainer = null;
                    _candidates = new();
                    _candidatesSearched = false;
                    _noLyricsChosen = false;
                    _cover = null;
                    _palette = CoverPalette.Neutral;
                    _coverVersionLoaded = -1;
                    StartLyricLookup(snap, key);
                }
                else if (previous != null && previous.DurationMs == 0 && snap.DurationMs > 0 && _lyric == null)
                {
                    // Some players publish the length a moment later; it makes matching far more reliable.
                    StartLyricLookup(snap, key);
                }

                if (snap.CoverVersion != _coverVersionLoaded || songChanged) LoadCover();

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
        if (outgoing.Count > 0) DisplayChanged?.Invoke();
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
        var query = new SongQuery(snap.Title, snap.Artist, snap.Album, snap.DurationMs);
        _ = Task.Run(async () =>
        {
            LyricResult? result = null;
            List<LyricCandidate>? candidates = null;
            var noLyrics = false;
            try
            {
                // A lyric the user picked by hand for this song wins.
                var choice = LyricChoices.Get(query);
                if (choice.NoLyrics)
                {
                    noLyrics = true;
                }
                else if (choice.Candidate != null)
                {
                    var doc = await LyricRepository.FetchAsync(choice.Candidate, cts.Token);
                    if (doc != null) result = new LyricResult(doc, choice.Candidate);
                }
                // Then a matching file in the local lyrics folder.
                if (result == null && !noLyrics) result = LocalLyrics.Find(query);
                if (result == null && !noLyrics)
                {
                    (result, candidates) = await LyricRepository.FindAsync(query, cts.Token);
                }
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
                if (candidates != null)
                {
                    _candidates = candidates;
                    _candidatesSearched = true;
                }
                _noLyricsChosen = noLyrics;
                SetLyricLocked(result);
                lyricEvent = LyricEvent();
                stateEvent = ControllerStateEvent();
            }
            Broadcast(lyricEvent);
            Broadcast(stateEvent);
            DisplayChanged?.Invoke();
        });
    }

    private void SetLyricLocked(LyricResult? result)
    {
        _lyric = result;
        _lyricContainer = null;
        _displayDoc = null;
        _lyricStatus = _noLyricsChosen ? "已設定這首歌不顯示歌詞"
            : result == null ? "找不到同步歌詞"
            : $"歌詞：{result.SourceLabel}";
    }

    // ---------- manual lyric choice ----------

    /// <summary>The picker's list: every match found for the current song (searched now if needed).</summary>
    public async Task<JsonObject> CandidatesAsync(CancellationToken ct)
    {
        SongQuery? query;
        string? key;
        bool needSearch;
        lock (_gate)
        {
            query = _snap == null ? null : new SongQuery(_snap.Title, _snap.Artist, _snap.Album, _snap.DurationMs);
            key = _trackKey;
            needSearch = query != null && !_candidatesSearched;
        }
        if (query == null) return new JsonObject { ["hasSession"] = false, ["candidates"] = new JsonArray() };
        if (needSearch)
        {
            var found = await LyricRepository.SearchAllAsync(query, ct);
            lock (_gate)
            {
                if (key == _trackKey)
                {
                    _candidates = found;
                    _candidatesSearched = true;
                }
            }
        }
        lock (_gate)
        {
            var list = new JsonArray();
            foreach (var c in _candidates.Take(40)) list.Add(c.ToJson());
            return new JsonObject
            {
                ["hasSession"] = true,
                ["title"] = query.Title,
                ["artist"] = query.Artist,
                ["durationMs"] = query.DurationMs,
                ["current"] = _lyric == null ? null : new JsonObject { ["source"] = _lyric.Candidate.Source, ["id"] = _lyric.Candidate.Id },
                ["noLyrics"] = _noLyricsChosen,
                ["remembered"] = LyricChoices.Has(query),
                ["candidates"] = list,
            };
        }
    }

    /// <summary>Use this candidate for the current song, and remember it for next time.</summary>
    public async Task<bool> ChooseAsync(LyricCandidate candidate, CancellationToken ct)
    {
        SongQuery? query;
        string? key;
        lock (_gate)
        {
            query = _snap == null ? null : new SongQuery(_snap.Title, _snap.Artist, _snap.Album, _snap.DurationMs);
            key = _trackKey;
        }
        if (query == null) return false;
        LyricCandidate full;
        lock (_gate) full = _candidates.FirstOrDefault(c => c.Source == candidate.Source && c.Id == candidate.Id) ?? candidate;
        var doc = await LyricRepository.FetchAsync(full, ct);
        if (doc == null) return false;
        LyricChoices.Set(query, full);
        ApplyManual(key, new LyricResult(doc, full), noLyrics: false);
        return true;
    }

    /// <summary>Show no lyrics for this song (remembered).</summary>
    public void ChooseNone()
    {
        SongQuery? query;
        string? key;
        lock (_gate)
        {
            query = _snap == null ? null : new SongQuery(_snap.Title, _snap.Artist, _snap.Album, _snap.DurationMs);
            key = _trackKey;
        }
        if (query == null) return;
        LyricChoices.SetNone(query);
        ApplyManual(key, null, noLyrics: true);
    }

    /// <summary>Search the current song again (e.g. after the local lyrics folder changed).</summary>
    public void RefreshLyrics()
    {
        MediaSnapshot? snap;
        string? key;
        lock (_gate)
        {
            snap = _snap;
            key = _trackKey;
        }
        if (snap == null || key == null) return;
        LyricRepository.Forget(new SongQuery(snap.Title, snap.Artist, snap.Album, snap.DurationMs));
        string stateEvent;
        lock (_gate)
        {
            if (key != _trackKey) return;
            StartLyricLookup(snap, key);
            stateEvent = ControllerStateEvent();
        }
        Broadcast(stateEvent);
    }

    /// <summary>Forget the hand-picked lyric for this song and search again.</summary>
    public void ResetChoice()
    {
        MediaSnapshot? snap;
        string? key;
        lock (_gate)
        {
            snap = _snap;
            key = _trackKey;
        }
        if (snap == null || key == null) return;
        LyricChoices.Remove(new SongQuery(snap.Title, snap.Artist, snap.Album, snap.DurationMs));
        LyricRepository.Forget(new SongQuery(snap.Title, snap.Artist, snap.Album, snap.DurationMs));
        string stateEvent;
        lock (_gate)
        {
            if (key != _trackKey) return;
            _noLyricsChosen = false;
            StartLyricLookup(snap, key);
            stateEvent = ControllerStateEvent();
        }
        Broadcast(stateEvent);
    }

    private void ApplyManual(string? key, LyricResult? result, bool noLyrics)
    {
        string lyricEvent, stateEvent;
        lock (_gate)
        {
            if (key != _trackKey) return;
            CancelLyricLookup();
            _noLyricsChosen = noLyrics;
            SetLyricLocked(result);
            lyricEvent = LyricEvent();
            stateEvent = ControllerStateEvent();
        }
        Broadcast(lyricEvent);
        Broadcast(stateEvent);
        DisplayChanged?.Invoke();
    }

    // ---------- cover art ----------

    /// <summary>
    /// Reads the player's artwork. Requests are never dropped: one loop runs at a time and goes round
    /// again while new requests came in. A cover identical to the previous song's (and a different
    /// album) is the player still showing the old artwork, so it is read again for a few seconds
    /// instead of being shown; if it never changes, no cover is shown rather than a wrong one.
    /// </summary>
    private void LoadCover()
    {
        Interlocked.Increment(ref _coverRequests);
        if (Interlocked.CompareExchange(ref _coverLoading, 1, 0) != 0) return;
        _ = Task.Run(CoverLoopAsync);
    }

    private async Task CoverLoopAsync()
    {
        try
        {
            // Artwork often lands a moment after the title; give it that moment.
            await Task.Delay(150);
            string? lastKey = null;
            var staleReads = 0;
            while (true)
            {
                var requests = Volatile.Read(ref _coverRequests);
                string? key;
                int version;
                lock (_gate)
                {
                    key = _trackKey;
                    version = _snap?.CoverVersion ?? 0;
                }
                if (key == null) break;
                if (key != lastKey)
                {
                    lastKey = key;
                    staleReads = 0;
                }

                var cover = await _source.ReadCoverAsync();
                var retry = false;
                string? trackEvent = null, stateEvent = null;
                lock (_gate)
                {
                    if (key == _trackKey)
                    {
                        var stale = cover != null && _previousSongCover != null &&
                                    !string.Equals(_snap?.Album ?? "", _previousAlbum, StringComparison.Ordinal) &&
                                    cover.Value.Data.AsSpan().SequenceEqual(_previousSongCover);
                        // No artwork yet is also worth a few more looks.
                        if ((stale && staleReads < 20) || (cover == null && staleReads < 8))
                        {
                            staleReads++;
                            retry = true;
                        }
                        else
                        {
                            _coverVersionLoaded = version;
                            if (cover != null && !stale && (_cover == null || !_cover.AsSpan().SequenceEqual(cover.Value.Data)))
                            {
                                _cover = cover.Value.Data;
                                _coverMime = cover.Value.Mime;
                                _coverId++;
                                _coverKey = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(_cover))[..20].ToLowerInvariant();
                                _palette = CoverPalette.FromImage(_cover);
                                trackEvent = TrackEvent();
                                stateEvent = ControllerStateEvent();
                            }
                        }
                    }
                }
                if (trackEvent != null)
                {
                    Broadcast(trackEvent);
                    Broadcast(stateEvent!);
                    DisplayChanged?.Invoke();
                }
                if (retry)
                {
                    await Task.Delay(400);
                    continue;
                }
                if (Volatile.Read(ref _coverRequests) == requests) break;
            }
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
        finally
        {
            Volatile.Write(ref _coverLoading, 0);
        }
        // A request that came in just as the loop was finishing.
        bool again;
        lock (_gate) again = _snap != null && _snap.CoverVersion != _coverVersionLoaded;
        if (again) LoadCover();
    }

    public (byte[] Data, string Mime)? GetCover(string key)
    {
        lock (_gate)
        {
            return _cover != null && key == _coverKey ? (_cover, _coverMime) : null;
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
            if (body.TryGetProperty("traditional", out var tr) && (tr.ValueKind is JsonValueKind.True or JsonValueKind.False))
            {
                lyricChanged |= _settings.Traditional != tr.GetBoolean();
                _settings.Traditional = tr.GetBoolean();
                if (lyricChanged)
                {
                    _lyricContainer = null;
                    _displayDoc = null;
                }
            }
            if (body.TryGetProperty("offsetMs", out var o) && o.ValueKind == JsonValueKind.Number && o.TryGetInt32(out var offset))
            {
                offset = Math.Clamp(offset, -5000, 5000);
                offsetChanged = _settings.OffsetMs != offset;
                _settings.OffsetMs = offset;
            }
            if (body.TryGetProperty("audioReactive", out var ar) && (ar.ValueKind is JsonValueKind.True or JsonValueKind.False))
            {
                _settings.AudioReactive = ar.GetBoolean();
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
        DisplayChanged?.Invoke();
        SettingsChanged?.Invoke();
    }

    // ---------- desktop lyrics ----------

    /// <summary>What the desktop lyrics window draws (the lyrics already converted to Traditional when that's on).</summary>
    public DisplayState GetDisplay()
    {
        lock (_gate)
        {
            var snap = _snap;
            if (_lyric != null && _displayDoc == null)
            {
                var doc = _lyric.Doc;
                _displayDoc = _settings.Traditional && !LyricFormats.HasKana(doc)
                    ? LyricFormats.ConvertChinese(doc, ChineseConverter.ToTraditional)
                    : doc;
            }
            return new DisplayState(
                snap != null, snap?.Title ?? "", snap?.Artist ?? "", snap?.Playing ?? false,
                _lyric == null ? null : _displayDoc, _palette, _coverId);
        }
    }

    /// <summary>The lyric clock right now (player position minus the offset setting).</summary>
    public long LyricClockMs()
    {
        lock (_gate) return ClockMs();
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
            if (_cover != null) data["cover"] = $"/api/cover/{_coverKey}";
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
        // Everything goes to Folia as one lyric container (word timing + translation + romaji);
        // which subtitle line shows is each screen's own setting. Simplified → Traditional applies to
        // every source; Japanese originals (kana) and romaji are left alone.
        // A local .lrc goes to Folia untouched (its parser knows every LRC dialect), just converted.
        _lyricContainer ??= result.Doc.RawLrc is { } raw
            ? (_settings.Traditional && !LyricFormats.HasKana(result.Doc) ? ChineseConverter.ToTraditional(raw) : raw)
            : LyricFormats.ToFoliaContainer(
                _settings.Traditional ? LyricFormats.ConvertChinese(result.Doc, ChineseConverter.ToTraditional) : result.Doc);

        data["source"] = result.Candidate.Source;
        data["title"] = snap.Title;
        data["author"] = snap.Artist;
        if (snap.DurationMs > 0) data["duration"] = snap.DurationMs;
        data["hasLyric"] = true;
        data["lrc"] = _lyricContainer;
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
            ["album"] = snap?.Album ?? "",
            ["cover"] = _cover != null ? $"/api/cover/{_coverKey}" : null,
            ["playing"] = snap?.Playing ?? false,
            ["positionMs"] = snap?.LivePositionMs() ?? 0,
            ["durationMs"] = snap?.DurationMs ?? 0,
            ["canSeek"] = snap?.CanSeek ?? false,
            ["lyricStatus"] = _lyricStatus,
            ["settings"] = new JsonObject
            {

                ["traditional"] = _settings.Traditional,
                ["offsetMs"] = _settings.OffsetMs,
                ["audioReactive"] = _settings.AudioReactive,
            },
            ["lan"] = new JsonObject { ["enabled"] = lanEnabled, ["urls"] = urlArray },
        };
        return Envelope("ControllerState", data);
    }
}

internal sealed record DisplayState(bool HasSession, string Title, string Artist, bool Playing, LyricDoc? Doc, CoverPalette Palette, int CoverId);
