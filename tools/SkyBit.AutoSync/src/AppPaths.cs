using System;
using System.IO;
using System.Text;

namespace SkyBitAutoSync
{
    internal static class AppPaths
    {
        public static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SkyBit AutoSync");
        public static readonly string ConfigPath = Path.Combine(DataDir, "config.ini");
        public static readonly string LogPath = Path.Combine(DataDir, "autosync.log");
        public static readonly string StartupLogPath = Path.Combine(DataDir, "startup-error.log");

        public static void Ensure()
        {
            Directory.CreateDirectory(DataDir);
        }

        public static void AppendLog(string line)
        {
            try
            {
                Ensure();
                File.AppendAllText(LogPath, line + Environment.NewLine, Encoding.UTF8);
            }
            catch { }
        }

        public static void WriteStartupError(string text)
        {
            try
            {
                Ensure();
                File.AppendAllText(StartupLogPath, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + text + Environment.NewLine, Encoding.UTF8);
            }
            catch { }
        }
    }
}