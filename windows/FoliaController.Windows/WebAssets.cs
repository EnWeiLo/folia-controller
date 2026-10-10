using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;

namespace FoliaController;

/// <summary>Folia's web build and our page files, all embedded in the exe and served from memory.</summary>
internal sealed class WebAssets
{
    private readonly Dictionary<string, byte[]> _folia = new(StringComparer.Ordinal);
    private readonly Dictionary<string, byte[]> _web = new(StringComparer.OrdinalIgnoreCase);

    public byte[] IndexHtml { get; private set; } = Array.Empty<byte>();
    public byte[] LauncherHtml { get; private set; } = Array.Empty<byte>();
    public byte[] AppIcon { get; private set; } = Array.Empty<byte>();
    public int FoliaFileCount => _folia.Count;

    public static WebAssets Load()
    {
        var assets = new WebAssets();
        var assembly = Assembly.GetExecutingAssembly();
        foreach (var name in assembly.GetManifestResourceNames())
        {
            using var stream = assembly.GetManifestResourceStream(name);
            if (stream == null) continue;
            if (name == "folia.zip")
            {
                using var zip = new ZipArchive(stream, ZipArchiveMode.Read);
                foreach (var entry in zip.Entries)
                {
                    if (entry.FullName.EndsWith('/')) continue;
                    using var entryStream = entry.Open();
                    using var buffer = new MemoryStream();
                    entryStream.CopyTo(buffer);
                    assets._folia[entry.FullName.Replace('\\', '/').TrimStart('/')] = buffer.ToArray();
                }
            }
            else if (name.StartsWith("web/", StringComparison.Ordinal))
            {
                assets._web[name["web/".Length..]] = ReadAll(stream);
            }
            else if (name == "app.ico")
            {
                assets.AppIcon = ReadAll(stream);
            }
        }

        assets.LauncherHtml = assets._web.TryGetValue("launcher.html", out var launcher) ? launcher : Encoding.UTF8.GetBytes("missing launcher");
        assets.IndexHtml = assets._folia.TryGetValue("index.html", out var index)
            ? Encoding.UTF8.GetBytes(PrepareIndex(Encoding.UTF8.GetString(index)))
            : Encoding.UTF8.GetBytes(
                "<!doctype html><meta charset=utf-8><body style='background:#09090b;color:#eee;font-family:sans-serif;padding:24px'>" +
                "<h3>找不到 Folia 網頁資源</h3><p>這個 exe 打包時沒有包含 Folia。請用 GitHub Actions 重新建置。</p>");
        return assets;
    }

    /// <summary>
    /// Folia's index.html with our control bar added before Folia's own scripts, its viewport tag
    /// removed (overlay.js writes one sized for phones) and the PWA service worker left out
    /// (a cached worker would keep serving old files after an update).
    /// </summary>
    private static string PrepareIndex(string html)
    {
        html = Regex.Replace(html, @"<meta\s+name=[""']viewport[""'][^>]*>", "", RegexOptions.IgnoreCase);
        html = Regex.Replace(html, @"<script[^>]*registerSW[^>]*>\s*</script>", "", RegexOptions.IgnoreCase);
        html = Regex.Replace(html, @"<title>[^<]*</title>", "<title>Folia 控制器</title>", RegexOptions.IgnoreCase);
        var head = Regex.Match(html, @"<head[^>]*>", RegexOptions.IgnoreCase);
        // fc-tuning.js must run before Folia reads its address (it merges the panel's options into cfg).
        const string injection = "<link rel=\"stylesheet\" href=\"/__fc/overlay.css\"><link rel=\"stylesheet\" href=\"/__fc/fc-tuning.css\">" +
            "<script src=\"/__fc/fc-tuning-schema.js\"></script><script src=\"/__fc/fc-tuning.js\"></script>" +
            "<script src=\"/__fc/overlay.js\"></script>";
        return head.Success ? html.Insert(head.Index + head.Length, injection) : injection + html;
    }

    public byte[]? Folia(string path) => _folia.TryGetValue(path, out var data) ? data : null;

    public byte[]? Web(string name) => _web.TryGetValue(name, out var data) ? data : null;

    private static byte[] ReadAll(Stream stream)
    {
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return buffer.ToArray();
    }

    public static string MimeType(string path)
    {
        var ext = Path.GetExtension(path).ToLowerInvariant();
        return ext switch
        {
            ".html" or ".htm" => "text/html; charset=utf-8",
            ".js" or ".mjs" => "text/javascript; charset=utf-8",
            ".css" => "text/css; charset=utf-8",
            ".json" or ".map" => "application/json; charset=utf-8",
            ".webmanifest" => "application/manifest+json; charset=utf-8",
            ".wasm" => "application/wasm",
            ".svg" => "image/svg+xml; charset=utf-8",
            ".png" => "image/png",
            ".jpg" or ".jpeg" => "image/jpeg",
            ".webp" => "image/webp",
            ".gif" => "image/gif",
            ".avif" => "image/avif",
            ".ico" => "image/x-icon",
            ".woff" => "font/woff",
            ".woff2" => "font/woff2",
            ".ttf" => "font/ttf",
            ".otf" => "font/otf",
            ".mp3" => "audio/mpeg",
            ".ogg" => "audio/ogg",
            ".wav" => "audio/wav",
            ".mp4" => "video/mp4",
            ".webm" => "video/webm",
            ".txt" => "text/plain; charset=utf-8",
            ".onnx" or ".bin" => "application/octet-stream",
            _ => "application/octet-stream",
        };
    }
}
