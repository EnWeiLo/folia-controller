using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace FoliaController;

/// <summary>
/// "音樂律動": listens to what the PC is playing (WASAPI loopback of the default output, nothing is
/// recorded or stored) and turns it into the spectrum Folia's audio-reactive visuals use — the same
/// numbers a Web Audio AnalyserNode gives at fftSize 2048 / 44.1 kHz (Blackman window, smoothing
/// 0.8, -100…-30 dB as 0…255). fc-audio.js turns that into Folia's power and bands.
/// Runs only while a page listens and the setting is on.
/// </summary>
internal sealed class AudioCapture : IDisposable
{
    private const int FftSize = 2048;
    private const int Bins = 1024;
    private const int TickMs = 30;

    private readonly object _gate = new();
    private readonly float[] _ring = new float[FftSize * 4];
    private int _ringPos;
    private long _lastDataAt;
    private int _sampleRate = 48000;
    private WasapiLoopbackCapture? _capture;
    private string? _deviceId;
    private volatile bool _reopen;
    private System.Threading.Timer? _timer;
    private int _busy;
    private int _ticks;

    private readonly Func<bool> _isPlaying;
    private readonly double[] _window = new double[FftSize];
    private readonly double[] _re = new double[FftSize];
    private readonly double[] _im = new double[FftSize];
    private readonly double[] _smoothed = new double[FftSize / 2];
    private readonly byte[] _bytes = new byte[Bins];

    /// <summary>Gets each frame as JSON {"p":0|1,"s":"base64 of 1024 bytes"}.</summary>
    public Action<string> Send { get; set; } = _ => { };
    public string? LastError { get; private set; }
    public bool Running => _timer != null;

    public AudioCapture(Func<bool> isPlaying)
    {
        _isPlaying = isPlaying;
        // Blackman window, as Web Audio's AnalyserNode uses.
        for (var i = 0; i < FftSize; i++)
        {
            var x = 2 * Math.PI * i / FftSize;
            _window[i] = 0.42 - 0.5 * Math.Cos(x) + 0.08 * Math.Cos(2 * x);
        }
    }

    public void Start()
    {
        lock (_gate)
        {
            if (_timer != null) return;
            Open();
            _timer = new System.Threading.Timer(_ => Tick(), null, 0, TickMs);
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            _timer?.Dispose();
            _timer = null;
            Close();
            Array.Clear(_smoothed);
        }
    }

    public void Dispose() => Stop();

    // ---------- capture ----------

    private void Open()
    {
        Close();
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            var capture = new WasapiLoopbackCapture(device);
            _sampleRate = capture.WaveFormat.SampleRate;
            capture.DataAvailable += OnData;
            capture.RecordingStopped += (_, e) =>
            {
                if (e.Exception != null) LastError = e.Exception.Message;
                _reopen = true; // device unplugged, format changed, …
            };
            capture.StartRecording();
            _capture = capture;
            _deviceId = device.ID;
            LastError = null;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            CrashLog.Write(ex, fatal: false);
            _capture = null;
            _deviceId = null;
        }
    }

    private void Close()
    {
        var capture = _capture;
        _capture = null;
        if (capture == null) return;
        try
        {
            capture.DataAvailable -= OnData;
            capture.StopRecording();
            capture.Dispose();
        }
        catch (Exception)
        {
            // Already gone.
        }
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (sender is not WasapiLoopbackCapture capture) return;
        var format = capture.WaveFormat;
        var channels = Math.Max(1, format.Channels);
        var isFloat = format.Encoding == WaveFormatEncoding.IeeeFloat ||
                      (format.Encoding == WaveFormatEncoding.Extensible && format.BitsPerSample == 32);
        var bytesPerSample = format.BitsPerSample / 8;
        if (bytesPerSample is not (2 or 4)) return;
        var frameBytes = bytesPerSample * channels;
        var frames = e.BytesRecorded / frameBytes;
        lock (_ring)
        {
            for (var f = 0; f < frames; f++)
            {
                double sum = 0;
                var offset = f * frameBytes;
                for (var c = 0; c < channels; c++)
                {
                    var at = offset + c * bytesPerSample;
                    sum += isFloat
                        ? BitConverter.ToSingle(e.Buffer, at)
                        : bytesPerSample == 4 ? BitConverter.ToInt32(e.Buffer, at) / 2147483648.0 : BitConverter.ToInt16(e.Buffer, at) / 32768.0;
                }
                _ring[_ringPos] = (float)(sum / channels);
                _ringPos = (_ringPos + 1) % _ring.Length;
            }
            _lastDataAt = Environment.TickCount64;
        }
    }

    // ---------- analysis ----------

    private void Tick()
    {
        if (Interlocked.Exchange(ref _busy, 1) == 1) return;
        try
        {
            _ticks++;
            // Follow the default output (headphones plugged in, another device chosen…).
            if (_reopen || _ticks % 100 == 0 && DefaultDeviceChanged())
            {
                _reopen = false;
                lock (_gate) if (_timer != null) Open();
            }

            var playing = _isPlaying();
            if (!playing)
            {
                Array.Clear(_smoothed);
                // A slow heartbeat so pages know the feature is on (they show Folia's idle breathing).
                if (_ticks % 15 == 0) Send("{\"p\":0,\"s\":\"\"}");
                return;
            }
            Analyse();
            Send("{\"p\":1,\"s\":\"" + Convert.ToBase64String(_bytes) + "\"}");
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
        }
        finally
        {
            Volatile.Write(ref _busy, 0);
        }
    }

    private bool DefaultDeviceChanged()
    {
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            return enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).ID != _deviceId;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private void Analyse()
    {
        // The newest FftSize samples; WASAPI loopback sends nothing during silence, so stale = silent.
        lock (_ring)
        {
            var fresh = Environment.TickCount64 - _lastDataAt < 200;
            var start = (_ringPos - FftSize + _ring.Length) % _ring.Length;
            for (var i = 0; i < FftSize; i++)
            {
                _re[i] = fresh ? _ring[(start + i) % _ring.Length] * _window[i] : 0;
                _im[i] = 0;
            }
        }
        Fft(_re, _im);
        for (var k = 0; k < FftSize / 2; k++)
        {
            var magnitude = Math.Sqrt(_re[k] * _re[k] + _im[k] * _im[k]) / FftSize;
            _smoothed[k] = 0.8 * _smoothed[k] + 0.2 * magnitude;
        }
        // Resample to Folia's bins (44.1 kHz / 2048 ≈ 21.5 Hz each) whatever the device's rate.
        var scale = 44100.0 / _sampleRate;
        for (var i = 0; i < Bins; i++)
        {
            var src = i * scale;
            var lo = (int)src;
            double v;
            if (lo >= FftSize / 2 - 1) v = 0;
            else
            {
                var t = src - lo;
                v = _smoothed[lo] * (1 - t) + _smoothed[lo + 1] * t;
            }
            var db = v > 0 ? 20 * Math.Log10(v) : -200;
            _bytes[i] = (byte)Math.Clamp((db + 100) * 255 / 70, 0, 255);
        }
    }

    /// <summary>In-place iterative radix-2 FFT.</summary>
    private static void Fft(double[] re, double[] im)
    {
        var n = re.Length;
        for (int i = 1, j = 0; i < n; i++)
        {
            var bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j)
            {
                (re[i], re[j]) = (re[j], re[i]);
                (im[i], im[j]) = (im[j], im[i]);
            }
        }
        for (var len = 2; len <= n; len <<= 1)
        {
            var angle = -2 * Math.PI / len;
            var wr = Math.Cos(angle);
            var wi = Math.Sin(angle);
            for (var i = 0; i < n; i += len)
            {
                double cr = 1, ci = 0;
                for (var j = 0; j < len / 2; j++)
                {
                    var ar = re[i + j + len / 2] * cr - im[i + j + len / 2] * ci;
                    var ai = re[i + j + len / 2] * ci + im[i + j + len / 2] * cr;
                    re[i + j + len / 2] = re[i + j] - ar;
                    im[i + j + len / 2] = im[i + j] - ai;
                    re[i + j] += ar;
                    im[i + j] += ai;
                    var next = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = next;
                }
            }
        }
    }

    /// <summary>For the CI check: analyse a test tone without any audio device.</summary>
    public static byte[] AnalyseTone(double hz, int sampleRate)
    {
        var a = new AudioCapture(() => true) { _sampleRate = sampleRate };
        for (var i = 0; i < a._ring.Length; i++) a._ring[i] = (float)(0.5 * Math.Sin(2 * Math.PI * hz * i / sampleRate));
        a._ringPos = 0;
        a._lastDataAt = Environment.TickCount64;
        for (var n = 0; n < 30; n++) a.Analyse(); // let the smoothing settle
        return (byte[])a._bytes.Clone();
    }
}
