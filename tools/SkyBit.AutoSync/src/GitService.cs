using System;
using System.Diagnostics;
using System.Text;
using System.Threading.Tasks;

namespace SkyBitAutoSync
{
    internal sealed class GitService
    {
        public Task<RunResult> GitAsync(string repo, string args)
        {
            return RunAsync("git.exe", args, repo);
        }

        public Task<RunResult> CommandAsync(string repo, string command)
        {
            string escaped = (command ?? "").Replace("\"", "\\\"");
            return RunAsync("cmd.exe", "/D /S /C \"" + escaped + "\"", repo);
        }

        public Task<RunResult> RunAsync(string fileName, string arguments, string workingDirectory)
        {
            return Task.Run(delegate
            {
                RunResult result = new RunResult();
                StringBuilder output = new StringBuilder();
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo();
                    psi.FileName = fileName;
                    psi.Arguments = arguments;
                    psi.WorkingDirectory = workingDirectory;
                    psi.UseShellExecute = false;
                    psi.CreateNoWindow = true;
                    psi.RedirectStandardOutput = true;
                    psi.RedirectStandardError = true;

                    using (Process process = new Process())
                    {
                        process.StartInfo = psi;
                        process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e)
                        {
                            if (e.Data != null) lock (output) output.AppendLine(e.Data);
                        };
                        process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e)
                        {
                            if (e.Data != null) lock (output) output.AppendLine(e.Data);
                        };
                        process.Start();
                        process.BeginOutputReadLine();
                        process.BeginErrorReadLine();
                        process.WaitForExit();
                        result.ExitCode = process.ExitCode;
                    }
                }
                catch (Exception ex)
                {
                    result.ExitCode = -1;
                    output.AppendLine(ex.Message);
                }

                lock (output) result.Output = output.ToString().Trim();
                return result;
            });
        }

        public static string Quote(string value)
        {
            return "\"" + (value ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";
        }

        public static int CountStatusLines(string status)
        {
            if (string.IsNullOrWhiteSpace(status)) return 0;
            return status.Replace("\r", "").Split(new char[] { '\n' }, StringSplitOptions.RemoveEmptyEntries).Length;
        }

        public static string Tail(string text, int max)
        {
            if (string.IsNullOrEmpty(text)) return "";
            return text.Length <= max ? text : "..." + text.Substring(text.Length - max);
        }
    }
}