using System;
using System.Threading;
using System.Windows.Forms;

namespace SkyBitAutoSync
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            try
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
                Application.ThreadException += OnThreadException;
                AppDomain.CurrentDomain.UnhandledException += OnUnhandledException;
                Application.Run(new MainForm());
            }
            catch (Exception ex)
            {
                AppPaths.WriteStartupError("Startup: " + ex);
                MessageBox.Show("SkyBit AutoSync could not start.\r\n\r\n" + ex.Message + "\r\n\r\nSee %APPDATA%\\SkyBit AutoSync\\startup-error.log", "SkyBit AutoSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static void OnThreadException(object sender, ThreadExceptionEventArgs e)
        {
            AppPaths.WriteStartupError("UI: " + e.Exception);
            MessageBox.Show("SkyBit AutoSync error.\r\n\r\n" + e.Exception.Message, "SkyBit AutoSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }

        private static void OnUnhandledException(object sender, UnhandledExceptionEventArgs e)
        {
            AppPaths.WriteStartupError("Fatal: " + e.ExceptionObject);
        }
    }
}