using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace FoliaController;

/// <summary>The app itself: tray icon and menu, web server, media reader and the Folia window.</summary>
internal sealed class TrayContext : ApplicationContext
{
    private readonly Control _ui = new();
    private readonly AppSettings _settings;
    private readonly WebAssets _assets;
    private readonly SmtcSource _source = new();
    private readonly MediaHub _hub;
    private readonly WebServer _server;
    private readonly NotifyIcon _tray;
    private readonly Icon? _icon;
    private readonly RegisteredWaitHandle _showWait;
    private readonly ToolStripMenuItem _lanItem;
    private readonly ToolStripMenuItem _copyLanItem;
    private readonly ToolStripMenuItem _autoStartItem;
    private readonly ToolStripMenuItem _localFolderItem;
    private readonly ToolStripMenuItem _desktopLyricsItem;
    private readonly ToolStripMenuItem _lockLyricsItem;
    private MainForm? _form;
    private DesktopLyricsForm? _desktopLyrics;
    private readonly AudioCapture _audio;
    private int _audioListeners;
    private string[] _lanUrls = Array.Empty<string>();
    private bool _tooltipQueued;

    public TrayContext(EventWaitHandle showEvent, bool startInTray)
    {
        _ui.CreateControl(); // a handle to marshal background events onto the UI thread

        _settings = AppSettings.Load();
        _assets = WebAssets.Load();
        _icon = LoadIcon(_assets.AppIcon);
        _hub = new MediaHub(_source, _settings);
        _server = new WebServer(_assets, _hub);
        _hub.Broadcast = _server.Broadcast;
        _hub.LanInfo = () => (_server.Lan, _lanUrls);
        _hub.TrackChanged += QueueTooltip;
        LocalLyrics.Folder = () => _settings.LocalLyricsFolder;
        _audio = new AudioCapture(() => _hub.Current?.Playing ?? false) { Send = _server.BroadcastAudio };
        _server.AudioListenersChanged += count =>
        {
            _audioListeners = count;
            UpdateAudio();
        };
        _hub.SettingsChanged += UpdateAudio;

        StartServer(_settings.AllowLan);

        var menu = new ContextMenuStrip();
        var openItem = new ToolStripMenuItem("開啟 Folia 視窗", null, (_, _) => ShowWindow()) { Font = new Font(menu.Font, FontStyle.Bold) };
        var browserItem = new ToolStripMenuItem("在瀏覽器開啟", null, (_, _) => OpenInBrowser());
        _desktopLyricsItem = new ToolStripMenuItem("桌面歌詞", null, (_, _) => SetDesktopLyrics(_desktopLyrics == null));
        _lockLyricsItem = new ToolStripMenuItem("鎖定桌面歌詞（滑鼠可穿過）", null, (_, _) => ToggleLyricsLock());
        _lanItem = new ToolStripMenuItem("允許區網裝置連線（平板、手機）", null, (_, _) => ToggleLan());
        _copyLanItem = new ToolStripMenuItem("複製區網網址", null, (_, _) => CopyLanUrl());
        _autoStartItem = new ToolStripMenuItem("開機時自動啟動", null, (_, _) => ToggleAutoStart());
        _localFolderItem = new ToolStripMenuItem("本機歌詞資料夾…", null, (_, _) => ChooseLocalFolder());
        var exitItem = new ToolStripMenuItem("結束", null, (_, _) => Quit());
        menu.Items.AddRange(new ToolStripItem[]
        {
            openItem, browserItem, new ToolStripSeparator(),
            _desktopLyricsItem, _lockLyricsItem, new ToolStripSeparator(),
            _lanItem, _copyLanItem, new ToolStripSeparator(),
            _localFolderItem, _autoStartItem, new ToolStripSeparator(),
            exitItem,
        });
        menu.Opening += (_, _) => RefreshMenu();

        _tray = new NotifyIcon
        {
            Icon = _icon ?? SystemIcons.Application,
            Text = "Folia 控制器",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _tray.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) ShowWindow();
        };

        _showWait = ThreadPool.RegisterWaitForSingleObject(showEvent,
            (_, _) => RunOnUi(ShowWindow), null, Timeout.Infinite, executeOnlyOnce: false);

        _ = StartSourceAsync();
        if (_settings.DesktopLyrics) SetDesktopLyrics(true);
        if (!startInTray) ShowWindow();
    }

    private static Icon? LoadIcon(byte[] data)
    {
        if (data.Length == 0) return null;
        try
        {
            using var stream = new MemoryStream(data);
            return new Icon(stream);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private void RunOnUi(Action action)
    {
        try
        {
            if (_ui.IsHandleCreated) _ui.BeginInvoke(action);
        }
        catch (Exception)
        {
            // Shutting down.
        }
    }

    private async Task StartSourceAsync()
    {
        try
        {
            await _source.StartAsync();
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
            RunOnUi(() => _tray.ShowBalloonTip(8000, "Folia 控制器",
                "無法讀取 Windows 的媒體播放資訊（需要 Windows 10 1809 以上）。", ToolTipIcon.Warning));
        }
    }

    // ---------- server ----------

    private void StartServer(bool lan)
    {
        try
        {
            _server.Start(_settings.Port, lan);
        }
        catch (Exception ex) when (lan)
        {
            CrashLog.Write(ex, fatal: false);
            _server.Start(_settings.Port, lan: false);
        }
        _lanUrls = lan ? FindLanUrls(_server.Port) : Array.Empty<string>();
    }

    private string LocalUrl => $"http://127.0.0.1:{_server.Port}/";

    /// <summary>This PC's addresses on the home network (skips virtual adapters for VMs / WSL).</summary>
    private static string[] FindLanUrls(int port)
    {
        var urls = new List<(int Rank, string Url)>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
                var label = (nic.Name + " " + nic.Description).ToLowerInvariant();
                if (label.Contains("virtual") || label.Contains("vmware") || label.Contains("hyper-v") || label.Contains("vethernet") ||
                    label.Contains("wsl") || label.Contains("docker") || label.Contains("vpn") || label.Contains("tap-")) continue;
                foreach (var address in nic.GetIPProperties().UnicastAddresses)
                {
                    var ip = address.Address;
                    if (ip.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(ip)) continue;
                    var b = ip.GetAddressBytes();
                    if (b[0] == 169 && b[1] == 254) continue;
                    var isPrivate = b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168);
                    var rank = (isPrivate ? 0 : 2) + (nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 0 : 1);
                    urls.Add((rank, $"http://{ip}:{port}/"));
                }
            }
        }
        catch (Exception)
        {
        }
        return urls.OrderBy(u => u.Rank).Select(u => u.Url).Distinct().ToArray();
    }

    // ---------- menu actions ----------

    private void RefreshMenu()
    {
        _lanItem.Checked = _server.Lan;
        _copyLanItem.Enabled = _server.Lan && _lanUrls.Length > 0;
        _copyLanItem.Text = _copyLanItem.Enabled ? $"複製區網網址（{_lanUrls[0]}）" : "複製區網網址";
        try { _autoStartItem.Checked = AutoStart.IsEnabled(); } catch (Exception) { _autoStartItem.Checked = false; }
        var folder = _settings.LocalLyricsFolder;
        _localFolderItem.Text = string.IsNullOrEmpty(folder) ? "本機歌詞資料夾…（未設定）" : $"本機歌詞資料夾…（{Path.GetFileName(folder.TrimEnd('\\'))}）";
        _localFolderItem.Checked = !string.IsNullOrEmpty(folder);
        _desktopLyricsItem.Checked = _desktopLyrics != null;
        _lockLyricsItem.Enabled = _desktopLyrics != null;
        _lockLyricsItem.Checked = _settings.DesktopLyricsLocked;
    }

    // ---------- 音樂律動 ----------

    /// <summary>Capture only while a page listens and the setting is on.</summary>
    private void UpdateAudio()
    {
        try
        {
            if (_settings.AudioReactive && _audioListeners > 0) _audio.Start();
            else _audio.Stop();
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
    }

    // ---------- desktop lyrics ----------

    private void SetDesktopLyrics(bool on)
    {
        if (on && _desktopLyrics == null)
        {
            _desktopLyrics = new DesktopLyricsForm(_hub, _settings);
            _desktopLyrics.LockedByUser += () => _tray.ShowBalloonTip(6000, "桌面歌詞已鎖定",
                "滑鼠現在會直接穿過歌詞。要移動或調整，請在系統匣的 Folia 圖示按右鍵，取消「鎖定桌面歌詞」。", ToolTipIcon.Info);
            _desktopLyrics.ClosedByUser += () => RunOnUi(() => SetDesktopLyrics(false));
            _desktopLyrics.Show();
        }
        else if (!on && _desktopLyrics != null)
        {
            var form = _desktopLyrics;
            _desktopLyrics = null;
            form.Close();
            form.Dispose();
        }
        if (_settings.DesktopLyrics != on)
        {
            _settings.DesktopLyrics = on;
            _settings.Save();
        }
        if (_form != null && !_form.IsDisposed) _form.PostHostState();
    }

    private void ToggleLyricsLock()
    {
        if (_desktopLyrics == null) return;
        _desktopLyrics.SetLocked(!_settings.DesktopLyricsLocked);
    }

    private void ShowWindow()
    {
        if (_form == null || _form.IsDisposed)
        {
            _form = new MainForm(() => LocalUrl, _settings, _icon, OpenInBrowser, _hub.FocusPlayer)
            {
                ToggleDesktopLyrics = () => SetDesktopLyrics(_desktopLyrics == null),
                DesktopLyricsOn = () => _desktopLyrics != null,
            };
            _form.HiddenToTray += OnWindowHidden;
            _form.Show();
        }
        _form.ShowFromTray();
    }

    private void OnWindowHidden()
    {
        if (_settings.TrayHintShown) return;
        _settings.TrayHintShown = true;
        _settings.Save();
        _tray.ShowBalloonTip(6000, "Folia 控制器仍在背景執行",
            "點系統匣的 Folia 圖示就能再打開；要完全關閉請按右鍵 →「結束」。", ToolTipIcon.Info);
    }

    private void OpenInBrowser()
    {
        try { Process.Start(new ProcessStartInfo(LocalUrl) { UseShellExecute = true }); } catch (Exception) { }
    }

    private void ToggleLan()
    {
        var enable = !_server.Lan;
        StartServer(enable);
        _settings.AllowLan = _server.Lan;
        _settings.Save();
        _form?.Reload();
        _hub.NotifyLanChanged();
        if (enable && _server.Lan)
        {
            _tray.ShowBalloonTip(10000, "已允許區網裝置連線",
                (_lanUrls.Length > 0 ? $"平板或手機連同一個 Wi‑Fi，用瀏覽器打開 {_lanUrls[0]}" : "找不到這台電腦的區網位址。") +
                "\n如果 Windows 跳出防火牆詢問，請允許「私人網路」。", ToolTipIcon.Info);
        }
        else if (enable)
        {
            _tray.ShowBalloonTip(8000, "無法開啟區網連線", "連接埠被占用，已改回只有這台電腦可以使用。", ToolTipIcon.Warning);
        }
    }

    private void ChooseLocalFolder()
    {
        var current = _settings.LocalLyricsFolder;
        if (!string.IsNullOrEmpty(current))
        {
            var answer = MessageBox.Show(
                $"目前的本機歌詞資料夾：\n{current}\n\n按「是」換一個資料夾，按「否」停用本機歌詞。",
                "本機歌詞資料夾", MessageBoxButtons.YesNoCancel, MessageBoxIcon.Question);
            if (answer == DialogResult.Cancel) return;
            if (answer == DialogResult.No)
            {
                _settings.LocalLyricsFolder = null;
                _settings.Save();
                LocalLyrics.Invalidate();
                _hub.RefreshLyrics();
                return;
            }
        }
        using var dialog = new FolderBrowserDialog
        {
            Description = "選擇放 .lrc 歌詞檔的資料夾（子資料夾也會找）。檔名用「歌名」、「歌手 - 歌名」或「歌名 - 歌手」。",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false,
        };
        if (!string.IsNullOrEmpty(current) && Directory.Exists(current)) dialog.InitialDirectory = current;
        if (dialog.ShowDialog() != DialogResult.OK || string.IsNullOrEmpty(dialog.SelectedPath)) return;
        _settings.LocalLyricsFolder = dialog.SelectedPath;
        _settings.Save();
        LocalLyrics.Invalidate();
        _hub.RefreshLyrics();
        _tray.ShowBalloonTip(5000, "已設定本機歌詞資料夾", dialog.SelectedPath, ToolTipIcon.Info);
    }

    private void CopyLanUrl()
    {
        if (_lanUrls.Length == 0) return;
        try { Clipboard.SetText(_lanUrls[0]); } catch (Exception) { }
    }

    private void ToggleAutoStart()
    {
        try
        {
            AutoStart.Set(!AutoStart.IsEnabled());
        }
        catch (Exception ex)
        {
            CrashLog.Write(ex, fatal: false);
        }
    }

    private void QueueTooltip()
    {
        if (_tooltipQueued) return;
        _tooltipQueued = true;
        RunOnUi(() =>
        {
            _tooltipQueued = false;
            var snap = _hub.Current;
            var text = "Folia 控制器";
            if (snap != null && snap.Title.Length > 0)
            {
                text += (snap.Playing ? "\n▶ " : "\n❚❚ ") + snap.Title + (snap.Artist.Length > 0 ? " — " + snap.Artist : "");
            }
            _tray.Text = text.Length > 127 ? text[..126] + "…" : text;
        });
    }

    private void Quit()
    {
        _showWait.Unregister(null);
        if (_form != null && !_form.IsDisposed)
        {
            _form.AllowClose = true;
            _form.Close();
        }
        _desktopLyrics?.Close();
        _desktopLyrics?.Dispose();
        _desktopLyrics = null;
        _tray.Visible = false;
        _tray.Dispose();
        _audio.Dispose();
        _server.Stop();
        _source.Dispose();
        ExitThread();
    }
}
