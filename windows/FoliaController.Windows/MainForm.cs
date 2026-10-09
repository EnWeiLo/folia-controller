using System.Diagnostics;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace FoliaController;

/// <summary>The Folia window: a WebView2 showing this computer's Folia page, full screen on demand.</summary>
internal sealed class MainForm : Form
{
    private readonly WebView2 _web;
    private readonly Func<string> _url;
    private readonly AppSettings _settings;
    private readonly Action _openInBrowser;
    private readonly Action _focusPlayer;

    private bool _ready;
    private bool _fullscreen;
    private FormWindowState _restoreState;
    private Rectangle _restoreBounds;
    private double _renderScale = 1.0;

    /// <summary>Set when the app quits, so closing really closes instead of hiding.</summary>
    public bool AllowClose { get; set; }

    public MainForm(Func<string> url, AppSettings settings, Icon? icon, Action openInBrowser, Action focusPlayer)
    {
        _url = url;
        _settings = settings;
        _openInBrowser = openInBrowser;
        _focusPlayer = focusPlayer;

        Text = "Folia 控制器";
        BackColor = Color.Black;
        if (icon != null) Icon = icon;
        StartPosition = FormStartPosition.Manual;
        MinimumSize = new Size(LogicalToDeviceUnits(480), LogicalToDeviceUnits(300));
        RestoreBounds();

        _web = new WebView2
        {
            Dock = DockStyle.Fill,
            DefaultBackgroundColor = Color.Black,
        };
        Controls.Add(_web);

        Load += async (_, _) => await InitWebViewAsync();
        FormClosing += OnFormClosing;
        DpiChanged += (_, _) => ApplyZoom();
        ResizeEnd += (_, _) => SaveBounds();
    }

    private void RestoreBounds()
    {
        var b = _settings.WindowBounds;
        var bounds = b is { Length: 4 } ? new Rectangle(b[0], b[1], b[2], b[3]) : Rectangle.Empty;
        var visible = !bounds.IsEmpty && Screen.AllScreens.Any(s => s.WorkingArea.IntersectsWith(bounds) &&
                                                                    Rectangle.Intersect(s.WorkingArea, bounds).Width > 200);
        if (visible)
        {
            Bounds = bounds;
        }
        else
        {
            var area = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1280, 800);
            var size = new Size(Math.Min(LogicalToDeviceUnits(1280), area.Width - 80), Math.Min(LogicalToDeviceUnits(760), area.Height - 80));
            Bounds = new Rectangle(area.X + (area.Width - size.Width) / 2, area.Y + (area.Height - size.Height) / 2, size.Width, size.Height);
        }
        if (_settings.WindowMaximized) WindowState = FormWindowState.Maximized;
    }

    private void SaveBounds()
    {
        if (_fullscreen) return;
        if (WindowState == FormWindowState.Normal) _settings.WindowBounds = new[] { Left, Top, Width, Height };
        _settings.WindowMaximized = WindowState == FormWindowState.Maximized;
        _settings.Save();
    }

    private async Task InitWebViewAsync()
    {
        try
        {
            var env = await CoreWebView2Environment.CreateAsync(null, AppPaths.Ensure("WebView2"));
            await _web.EnsureCoreWebView2Async(env);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            var answer = MessageBox.Show(this,
                "這台電腦缺少 Microsoft Edge WebView2 Runtime，無法開啟 Folia 視窗。\n\n" +
                "要改用瀏覽器開啟嗎？（也可以到微軟網站下載安裝 WebView2 Runtime 後再試）",
                "Folia 控制器", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (answer == DialogResult.Yes) _openInBrowser();
            HideToTray();
            return;
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
            MessageBox.Show(this, "Folia 視窗無法啟動：" + ex.Message + "\n\n可以從系統匣選單改用瀏覽器開啟。",
                "Folia 控制器", MessageBoxButtons.OK, MessageBoxIcon.Error);
            HideToTray();
            return;
        }

        var core = _web.CoreWebView2;
        core.Settings.IsZoomControlEnabled = false;
        core.Settings.IsPinchZoomEnabled = false;
        core.Settings.IsSwipeNavigationEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsGeneralAutofillEnabled = false;
        core.Settings.IsPasswordAutosaveEnabled = false;
        core.WebMessageReceived += OnWebMessage;
        core.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
            OpenExternal(e.Uri);
        };
        core.NavigationStarting += (_, e) =>
        {
            // Only our own pages load in this window; anything else goes to the browser.
            if (Uri.TryCreate(e.Uri, UriKind.Absolute, out var target) &&
                Uri.TryCreate(_url(), UriKind.Absolute, out var home) &&
                target.Authority != home.Authority && target.Scheme is "http" or "https")
            {
                e.Cancel = true;
                OpenExternal(e.Uri);
            }
        };
        core.ProcessFailed += (_, e) =>
        {
            if (e.ProcessFailedKind is CoreWebView2ProcessFailedKind.RenderProcessExited or CoreWebView2ProcessFailedKind.RenderProcessUnresponsive)
            {
                BeginInvoke(new Action(() => _web.CoreWebView2?.Reload()));
            }
        };

        ApplyZoom();
        _ready = true;
        core.Navigate(_url());
    }

    /// <summary>The server moved (LAN switched on or off): load the page from the new address.</summary>
    public void Reload()
    {
        if (_ready) _web.CoreWebView2.Navigate(_url());
    }

    private static void OpenExternal(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch (Exception) { }
    }

    // ---------- messages from overlay.js ----------

    private void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        try
        {
            using var doc = JsonDocument.Parse(e.WebMessageAsJson);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("type", out var typeEl)) return;
            switch (typeEl.GetString())
            {
                case "quality":
                    if (root.TryGetProperty("renderScale", out var scale) && scale.ValueKind == JsonValueKind.Number)
                    {
                        _renderScale = Math.Clamp(scale.GetDouble(), 0.4, 1.0);
                        ApplyZoom();
                    }
                    break;
                case "fullscreen":
                    SetFullscreen(!_fullscreen);
                    break;
                case "focusPlayer":
                    _focusPlayer();
                    break;
            }
        }
        catch (JsonException)
        {
        }
    }

    /// <summary>
    /// Makes one CSS px = one physical px × quality. Folia sizes itself for a 1920 px wide canvas and
    /// pins devicePixelRatio, so at 125–200 % display scaling it would otherwise render at the smaller
    /// CSS size and be stretched (blurry). Lower quality = fewer pixels = smoother.
    /// </summary>
    private void ApplyZoom()
    {
        var dpiScale = DeviceDpi / 96.0;
        var zoom = Math.Clamp(1.0 / (dpiScale * _renderScale), 0.25, 5.0);
        if (Math.Abs(_web.ZoomFactor - zoom) > 0.001) _web.ZoomFactor = zoom;
        PostHostState();
    }

    private void PostHostState()
    {
        if (!_ready || _web.CoreWebView2 == null) return;
        var json = JsonSerializer.Serialize(new { type = "host", zoom = _web.ZoomFactor, fullscreen = _fullscreen });
        try { _web.CoreWebView2.PostWebMessageAsJson(json); } catch (Exception) { }
    }

    // ---------- full screen ----------

    private void SetFullscreen(bool on)
    {
        if (on == _fullscreen) return;
        SuspendLayout();
        if (on)
        {
            _restoreState = WindowState;
            if (WindowState != FormWindowState.Normal) WindowState = FormWindowState.Normal;
            _restoreBounds = Bounds;
            FormBorderStyle = FormBorderStyle.None;
            Bounds = Screen.FromControl(this).Bounds;
        }
        else
        {
            FormBorderStyle = FormBorderStyle.Sizable;
            Bounds = _restoreBounds;
            WindowState = _restoreState;
        }
        _fullscreen = on;
        ResumeLayout();
        PostHostState();
    }

    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        // Only reached when focus is outside the page (the page handles its own keys).
        if (keyData == Keys.F11)
        {
            SetFullscreen(!_fullscreen);
            return true;
        }
        if (keyData == Keys.Escape && _fullscreen)
        {
            SetFullscreen(false);
            return true;
        }
        return base.ProcessCmdKey(ref msg, keyData);
    }

    // ---------- tray ----------

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        SaveBounds();
        if (!AllowClose && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            HideToTray();
        }
    }

    public event Action? HiddenToTray;

    private async void HideToTray()
    {
        Hide();
        HiddenToTray?.Invoke();
        // Freeze the page while hidden: no animation work, no GPU use.
        try
        {
            if (_ready && _web.CoreWebView2 != null)
            {
                await Task.Delay(300);
                if (!Visible) await _web.CoreWebView2.TrySuspendAsync();
            }
        }
        catch (Exception)
        {
            // Not suspendable right now; it is merely hidden.
        }
    }

    public void ShowFromTray()
    {
        if (!Visible) Show();
        if (WindowState == FormWindowState.Minimized) WindowState = _settings.WindowMaximized ? FormWindowState.Maximized : FormWindowState.Normal;
        Activate();
        BringToFront();
        try
        {
            var core = _ready ? _web.CoreWebView2 : null;
            if (core != null)
            {
                if (core.IsSuspended) core.Resume();
                // Anything that happened while frozen: ask for a full refresh.
                core.PostWebMessageAsJson("{\"type\":\"resync\"}");
                PostHostState();
            }
        }
        catch (Exception)
        {
        }
        _web.Focus();
    }
}

/// <summary>Brings a player's window to the front from its media-session app id.</summary>
internal static class WindowFocus
{
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool IsIconic(IntPtr hWnd);

    public static void BringAppToFront(string? appId)
    {
        if (string.IsNullOrWhiteSpace(appId)) return;
        try
        {
            if (appId.Contains('!'))
            {
                // Store app: "Package_xxx!App"
                Process.Start(new ProcessStartInfo("explorer.exe", "shell:AppsFolder\\" + appId) { UseShellExecute = true });
                return;
            }
            var name = Path.GetFileNameWithoutExtension(appId);
            if (name.Equals("chrome", StringComparison.OrdinalIgnoreCase) || name.Equals("msedge", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("firefox", StringComparison.OrdinalIgnoreCase) || name.Equals("spotify", StringComparison.OrdinalIgnoreCase))
            {
                name = name.ToLowerInvariant();
            }
            foreach (var process in Process.GetProcessesByName(name))
            {
                var handle = process.MainWindowHandle;
                if (handle == IntPtr.Zero) continue;
                if (IsIconic(handle)) ShowWindow(handle, 9); // SW_RESTORE
                SetForegroundWindow(handle);
                return;
            }
        }
        catch (Exception)
        {
            // Best effort.
        }
    }
}
