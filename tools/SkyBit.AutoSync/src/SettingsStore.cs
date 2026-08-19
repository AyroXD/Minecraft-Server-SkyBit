using System;
using System.IO;
using System.Text;
using Microsoft.Win32;

namespace SkyBitAutoSync
{
    internal static class SettingsStore
    {
        public static AppSettings Load()
        {
            AppSettings settings = new AppSettings();
            if (!File.Exists(AppPaths.ConfigPath)) return settings;
            try
            {
                foreach (string raw in File.ReadAllLines(AppPaths.ConfigPath, Encoding.UTF8))
                {
                    int i = raw.IndexOf('=');
                    if (i <= 0) continue;
                    string key = raw.Substring(0, i);
                    string value = raw.Substring(i + 1);
                    if (key == "repo") settings.RepoPath = Decode(value);
                    else if (key == "branch") settings.Branch = Decode(value);
                    else if (key == "delay") { int n; if (int.TryParse(value, out n)) settings.DelaySeconds = Math.Max(5, Math.Min(600, n)); }
                    else if (key == "gate") settings.BuildGate = ParseBool(value);
                    else if (key == "build") settings.BuildCommand = Decode(value);
                    else if (key == "prefix") settings.CommitPrefix = Decode(value);
                    else if (key == "startup") settings.StartWithWindows = ParseBool(value);
                    else if (key == "autostart") settings.AutoStart = ParseBool(value);
                }
            }
            catch (Exception ex)
            {
                AppPaths.WriteStartupError("Config load: " + ex);
            }
            return settings;
        }

        public static void Save(AppSettings s, string executablePath)
        {
            AppPaths.Ensure();
            StringBuilder text = new StringBuilder();
            text.AppendLine("repo=" + Encode(s.RepoPath));
            text.AppendLine("branch=" + Encode(s.Branch));
            text.AppendLine("delay=" + s.DelaySeconds);
            text.AppendLine("gate=" + s.BuildGate);
            text.AppendLine("build=" + Encode(s.BuildCommand));
            text.AppendLine("prefix=" + Encode(s.CommitPrefix));
            text.AppendLine("startup=" + s.StartWithWindows);
            text.AppendLine("autostart=" + s.AutoStart);
            File.WriteAllText(AppPaths.ConfigPath, text.ToString(), Encoding.UTF8);

            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Run", true))
                {
                    if (s.StartWithWindows) key.SetValue("SkyBitAutoSync", "\"" + executablePath + "\"");
                    else key.DeleteValue("SkyBitAutoSync", false);
                }
            }
            catch (Exception ex)
            {
                AppPaths.AppendLog("[settings] Windows startup update failed: " + ex.Message);
            }
        }

        private static bool ParseBool(string value)
        {
            bool parsed;
            return bool.TryParse(value, out parsed) && parsed;
        }

        private static string Encode(string value)
        {
            return Convert.ToBase64String(Encoding.UTF8.GetBytes(value ?? ""));
        }

        private static string Decode(string value)
        {
            try { return Encoding.UTF8.GetString(Convert.FromBase64String(value)); }
            catch { return ""; }
        }
    }
}