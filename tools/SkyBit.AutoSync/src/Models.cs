namespace SkyBitAutoSync
{
    internal sealed class AppSettings
    {
        public string RepoPath = "";
        public string Branch = "main";
        public int DelaySeconds = 20;
        public bool BuildGate = false;
        public string BuildCommand = "powershell.exe -ExecutionPolicy Bypass -File .\\management\\Build.ps1";
        public string CommitPrefix = "SkyBit AutoSync";
        public bool StartWithWindows = false;
        public bool AutoStart = false;
    }

    internal sealed class RunResult
    {
        public int ExitCode;
        public string Output = "";
        public bool Success { get { return ExitCode == 0; } }
    }
}