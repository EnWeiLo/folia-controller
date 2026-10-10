using System.Diagnostics;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace FoliaController;

/// <summary>
/// Desktop lyrics: a borderless, always-on-top, per-pixel transparent window with two lines (the
/// lyric and its translation). Moving the mouse onto the words shows a toolbar; when locked, the
/// mouse passes straight through to whatever is underneath.
/// </summary>
internal sealed class DesktopLyricsForm : Form
{
    private const int WS_EX_LAYERED = 0x80000;
    private const int WS_EX_TRANSPARENT = 0x20;
    private const int WS_EX_TOOLWINDOW = 0x80;
    private const int WS_EX_NOACTIVATE = 0x8000000;
    private const int WS_EX_TOPMOST = 0x8;
    private const int GWL_EXSTYLE = -20;
    private const int WM_MOUSEACTIVATE = 0x21;
    private const int WM_NCLBUTTONDOWN = 0xA1;
    private const int HTCAPTION = 2;
    private const int MA_NOACTIVATE = 3;
    private const int TransitionMs = 260;

    private readonly MediaHub _hub;
    private readonly AppSettings _settings;
    private readonly DesktopLyricsRenderer _renderer = new();
    private readonly System.Windows.Forms.Timer _timer = new();
    private readonly Stopwatch _clock = Stopwatch.StartNew();

    private DisplayState _state;
    private LyricFrame? _current;
    private LyricFrame? _previous;
    private long _transitionStart = -TransitionMs;
    private bool _hover;
    private ToolbarButton _hoverButton;
    private bool _hiddenForFullscreen;
    private long _nextFullscreenCheck;
    private bool _dirty = true;

    private IntPtr _dib = IntPtr.Zero;
    private IntPtr _bits = IntPtr.Zero;
    private IntPtr _memDc = IntPtr.Zero;
    private IntPtr _oldBitmap = IntPtr.Zero;
    private Bitmap? _surface;
    private Size _surfaceSize;

    /// <summary>For the CI check: the first drawing error, and how many frames reached the screen.</summary>
    public string? LastError { get; private set; }
    public int PresentCount { get; private set; }
    public string? CurrentText => _current == null ? null : _current.Main + (_current.Sub.Length > 0 ? " / " + _current.Sub : "");
    public bool HiddenForFullscreen => _hiddenForFullscreen;

    /// <summary>The lock button was pressed (the tray shows how to unlock).</summary>
    public event Action? LockedByUser;
    /// <summary>The close button was pressed.</summary>
    public event Action? ClosedByUser;

    public DesktopLyricsForm(MediaHub hub, AppSettings settings)
    {
        _hub = hub;
        _settings = settings;
        _state = hub.GetDisplay();
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        Text = "Folia 桌面歌詞";
        _renderer.TextSize = settings.DesktopLyricsSize;

        _hub.DisplayChanged += OnDisplayChanged;
        _timer.Interval = 16;
        _timer.Tick += (_, _) => Tick();
        DpiChanged += (_, _) => Relayout(keepCenter: true);
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST;
            // Form's own constructor asks for CreateParams before ours has run, so _settings can be null here.
            if (_settings?.DesktopLyricsLocked == true) cp.ExStyle |= WS_EX_TRANSPARENT;
            return cp;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Relayout(keepCenter: false);
        _timer.Start();
        Tick();
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_MOUSEACTIVATE)
        {
            m.Result = (IntPtr)MA_NOACTIVATE;
            return;
        }
        base.WndProc(ref m);
    }

    // ---------- size and position ----------

    private void Relayout(bool keepCenter)
    {
        _renderer.Scale = DeviceDpi / 96f;
        _renderer.TextSize = _settings.DesktopLyricsSize;
        var screen = Screen.FromPoint(Bounds.Width > 0 ? new Point(Left + Width / 2, Top + Height / 2) : Cursor.Position);
        var size = _renderer.WindowSize(screen.WorkingArea);
        Point location;
        if (keepCenter && Width > 0)
        {
            location = new Point(Left + (Width - size.Width) / 2, Bottom - size.Height);
        }
        else if (_settings.DesktopLyricsPosition is { Length: 2 } saved && IsOnScreen(new Rectangle(saved[0], saved[1], size.Width, size.Height)))
        {
            location = new Point(saved[0], saved[1]);
        }
        else
        {
            var area = Screen.PrimaryScreen?.WorkingArea ?? screen.WorkingArea;
            location = new Point(area.Left + (area.Width - size.Width) / 2, area.Bottom - size.Height - (int)(48 * _renderer.Scale));
        }
        Bounds = new Rectangle(location, size);
        EnsureSurface(size);
        Draw();
    }

    private static bool IsOnScreen(Rectangle rect) =>
        Screen.AllScreens.Any(s => s.WorkingArea.IntersectsWith(new Rectangle(rect.X + rect.Width / 4, rect.Y, rect.Width / 2, rect.Height)));

    private void SavePosition()
    {
        _settings.DesktopLyricsPosition = new[] { Left, Top };
        _settings.Save();
    }

    public void SetLocked(bool locked)
    {
        _settings.DesktopLyricsLocked = locked;
        _settings.Save();
        if (!IsHandleCreated) return;
        var style = GetWindowLong(Handle, GWL_EXSTYLE);
        style = locked ? style | WS_EX_TRANSPARENT : style & ~WS_EX_TRANSPARENT;
        SetWindowLong(Handle, GWL_EXSTYLE, style);
        _hover = false;
        _hoverButton = ToolbarButton.None;
        Draw();
    }

    public void ChangeSize(int delta)
    {
        var size = Math.Clamp(_settings.DesktopLyricsSize + delta, DesktopLyricsRenderer.MinSize, DesktopLyricsRenderer.MaxSize);
        if (size == _settings.DesktopLyricsSize) return;
        _settings.DesktopLyricsSize = size;
        Relayout(keepCenter: true);
        SavePosition();
    }

    // ---------- updates ----------

    private void OnDisplayChanged()
    {
        try
        {
            if (IsHandleCreated && !IsDisposed) BeginInvoke(new Action(() => { _state = _hub.GetDisplay(); _dirty = true; Tick(); }));
        }
        catch (Exception)
        {
            // Closing.
        }
    }

    private void Tick()
    {
        var now = _clock.ElapsedMilliseconds;
        if (now >= _nextFullscreenCheck)
        {
            _nextFullscreenCheck = now + 1000;
            var hide = FullscreenAppRunning();
            if (hide != _hiddenForFullscreen)
            {
                _hiddenForFullscreen = hide;
                _dirty = true;
            }
        }

        var clock = _hub.LyricClockMs();
        var frame = DesktopLyricsModel.FrameAt(_state, clock);
        if (frame?.Key != _current?.Key || frame?.Main != _current?.Main || frame?.Sub != _current?.Sub)
        {
            _previous = _current;
            _current = frame;
            _transitionStart = now;
            _dirty = true;
        }
        var animating = now - _transitionStart < TransitionMs + 20 ||
                        (_state.Playing && _current is { Words.Count: > 0 });
        _timer.Interval = animating || _hover ? 16 : 100;
        if (animating || _dirty) Draw();
    }

    private void Draw()
    {
        if (!IsHandleCreated || _surface == null) return;
        var now = _clock.ElapsedMilliseconds;
        var blank = _hiddenForFullscreen || (_current == null && !_hover);
        var input = blank
            ? new RenderInput(null, null, 1, 0, _state.Palette, false, false, ToolbarButton.None)
            : new RenderInput(_current, _previous, (now - _transitionStart) / (double)TransitionMs, _hub.LyricClockMs(),
                _state.Palette, _hover && !_settings.DesktopLyricsLocked, _state.Playing, _hoverButton);
        using (var g = Graphics.FromImage(_surface))
        {
            _renderer.Render(g, _surfaceSize, input);
        }
        _dirty = false;
        Present();
    }

    // ---------- mouse ----------

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (_settings.DesktopLyricsLocked) return;
        var button = _renderer.HitTest(e.Location, _surfaceSize);
        if (!_hover || button != _hoverButton)
        {
            _hover = true;
            _hoverButton = button;
            Cursor = button == ToolbarButton.None ? Cursors.SizeAll : Cursors.Hand;
            Draw();
        }
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        base.OnMouseLeave(e);
        _hover = false;
        _hoverButton = ToolbarButton.None;
        Draw();
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button != MouseButtons.Left || _settings.DesktopLyricsLocked) return;
        var button = _renderer.HitTest(e.Location, _surfaceSize);
        if (button != ToolbarButton.None)
        {
            Press(button);
            return;
        }
        // Drag the window by the lyrics.
        ReleaseCapture();
        SendMessage(Handle, WM_NCLBUTTONDOWN, (IntPtr)HTCAPTION, IntPtr.Zero);
        SavePosition();
    }

    private void Press(ToolbarButton button)
    {
        switch (button)
        {
            case ToolbarButton.Prev: _ = _hub.ControlAsync("prev", 0); break;
            case ToolbarButton.PlayPause: _ = _hub.ControlAsync("toggle", 0); break;
            case ToolbarButton.Next: _ = _hub.ControlAsync("next", 0); break;
            case ToolbarButton.Smaller: ChangeSize(-4); break;
            case ToolbarButton.Bigger: ChangeSize(4); break;
            case ToolbarButton.Lock:
                SetLocked(true);
                LockedByUser?.Invoke();
                break;
            case ToolbarButton.Close: ClosedByUser?.Invoke(); break;
        }
    }

    // ---------- full-screen apps ----------

    [DllImport("shell32.dll")]
    private static extern int SHQueryUserNotificationState(out int state);

    /// <summary>A game, video or presentation is full screen (QUNS_BUSY, D3D full screen, presentation mode).</summary>
    private static bool FullscreenAppRunning()
    {
        try
        {
            return SHQueryUserNotificationState(out var state) == 0 && state is 2 or 3 or 4;
        }
        catch (Exception)
        {
            return false;
        }
    }

    // ---------- per-pixel alpha window ----------

    private void EnsureSurface(Size size)
    {
        if (_surface != null && _surfaceSize == size) return;
        FreeSurface();
        var info = new BITMAPINFO
        {
            biSize = 40, // sizeof(BITMAPINFOHEADER); the whole struct (44) makes CreateDIBSection fail
            biWidth = size.Width,
            biHeight = -size.Height, // top-down
            biPlanes = 1,
            biBitCount = 32,
        };
        var screenDc = GetDC(IntPtr.Zero);
        _memDc = CreateCompatibleDC(screenDc);
        ReleaseDC(IntPtr.Zero, screenDc);
        _dib = CreateDIBSection(_memDc, ref info, 0, out _bits, IntPtr.Zero, 0);
        if (_dib == IntPtr.Zero || _bits == IntPtr.Zero)
        {
            LastError = $"CreateDIBSection failed ({Marshal.GetLastWin32Error()}) for {size.Width}x{size.Height}";
            CrashLog.Write(new InvalidOperationException(LastError), fatal: false);
            DeleteDC(_memDc);
            _memDc = IntPtr.Zero;
            _dib = IntPtr.Zero;
            return;
        }
        _oldBitmap = SelectObject(_memDc, _dib);
        // GDI+ draws straight into the DIB, premultiplied, which is what UpdateLayeredWindow wants.
        _surface = new Bitmap(size.Width, size.Height, size.Width * 4, PixelFormat.Format32bppPArgb, _bits);
        _surfaceSize = size;
    }

    private void FreeSurface()
    {
        _surface?.Dispose();
        _surface = null;
        if (_memDc != IntPtr.Zero)
        {
            SelectObject(_memDc, _oldBitmap);
            DeleteDC(_memDc);
            _memDc = IntPtr.Zero;
        }
        if (_dib != IntPtr.Zero)
        {
            DeleteObject(_dib);
            _dib = IntPtr.Zero;
        }
    }

    private void Present()
    {
        var size = new SIZE { cx = _surfaceSize.Width, cy = _surfaceSize.Height };
        var source = new POINT();
        var destination = new POINT { x = Left, y = Top };
        var blend = new BLENDFUNCTION { BlendOp = 0, BlendFlags = 0, SourceConstantAlpha = 255, AlphaFormat = 1 };
        var screenDc = GetDC(IntPtr.Zero);
        var ok = UpdateLayeredWindow(Handle, screenDc, ref destination, ref size, _memDc, ref source, 0, ref blend, 2);
        var error = ok ? 0 : Marshal.GetLastWin32Error();
        ReleaseDC(IntPtr.Zero, screenDc);
        PresentCount++;
        if (!ok && LastError == null)
        {
            LastError = $"UpdateLayeredWindow failed ({error})";
            CrashLog.Write(new InvalidOperationException(LastError), fatal: false);
        }
    }

    protected override void OnLocationChanged(EventArgs e)
    {
        base.OnLocationChanged(e);
        if (IsHandleCreated && _surface != null) Present();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _hub.DisplayChanged -= OnDisplayChanged;
            _timer.Dispose();
            _renderer.Dispose();
        }
        FreeSurface();
        base.Dispose(disposing);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int x, y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct SIZE { public int cx, cy; }

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    private struct BLENDFUNCTION { public byte BlendOp, BlendFlags, SourceConstantAlpha, AlphaFormat; }

    [StructLayout(LayoutKind.Sequential)]
    private struct BITMAPINFO
    {
        public int biSize, biWidth, biHeight;
        public short biPlanes, biBitCount;
        public int biCompression, biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant;
        public int bmiColors;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UpdateLayeredWindow(IntPtr hwnd, IntPtr hdcDst, ref POINT pptDst, ref SIZE psize,
        IntPtr hdcSrc, ref POINT pprSrc, int crKey, ref BLENDFUNCTION pblend, int dwFlags);

    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr hDC);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr hdc);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr hDC, IntPtr hObject);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr hObject);
    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateDIBSection(IntPtr hdc, ref BITMAPINFO pbmi, uint usage, out IntPtr bits, IntPtr section, uint offset);
    [DllImport("user32.dll")] private static extern bool ReleaseCapture();
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] private static extern int GetWindowLong(IntPtr hWnd, int index);
    [DllImport("user32.dll")] private static extern int SetWindowLong(IntPtr hWnd, int index, int value);
}
