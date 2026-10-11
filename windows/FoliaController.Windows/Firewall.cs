using System.Diagnostics;

namespace FoliaController;

/// <summary>
/// Windows Defender Firewall for the LAN pages. Windows asks once per exe file whether it may take
/// connections; each release is a new file (Flowrics-&lt;n&gt;.exe), and a dismissed prompt leaves block
/// rules behind, so tablets can't connect. The fix (with one UAC prompt) removes this exe's own
/// rules and allows our ports — a port rule keeps working after the next update.
/// </summary>
internal static class Firewall
{
    private const string RuleName = "Flowrics LAN";
    public const int PortRange = 20;

    /// <summary>True if our allow rule exists (reading rules needs no admin).</summary>
    public static bool HasRule()
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{RuleName}\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            });
            if (p == null) return true;
            p.StandardOutput.ReadToEnd();
            p.WaitForExit(10000);
            return p.ExitCode == 0;
        }
        catch (Exception)
        {
            return true; // can't tell: don't nag
        }
    }

    /// <summary>Opens the ports for every network profile. False if the user said no to UAC or it failed.</summary>
    public static bool Fix(int port)
    {
        var exe = Environment.ProcessPath ?? Application.ExecutablePath;
        var commands = string.Join(" & ",
            $"netsh advfirewall firewall delete rule name=all program=\"{exe}\" dir=in",
            $"netsh advfirewall firewall delete rule name=\"{RuleName}\"",
            $"netsh advfirewall firewall add rule name=\"{RuleName}\" dir=in action=allow protocol=TCP localport={port}-{port + PortRange - 1} profile=any",
            $"netsh advfirewall firewall add rule name=\"{RuleName}\" dir=in action=allow program=\"{exe}\" profile=any");
        try
        {
            using var p = Process.Start(new ProcessStartInfo("cmd.exe", "/c " + commands)
            {
                Verb = "runas",
                UseShellExecute = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            });
            p?.WaitForExit(30000);
        }
        catch (Exception ex)
        {
            // ERROR_CANCELLED: the user said no to UAC
            CrashLog.Write(ex, fatal: false);
            return false;
        }
        return HasRule();
    }
}
