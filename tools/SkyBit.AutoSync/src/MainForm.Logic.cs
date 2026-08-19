using System;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace SkyBitAutoSync
{
    internal sealed partial class MainForm
    {
        private readonly GitService git = new GitService();
        private Timer pollTimer;
        private bool watching;
        private bool busy;
        private string lastFingerprint = "";
        private DateTime changedAt = DateTime.MinValue;

        public MainForm()
        {
            AppPaths.Ensure();
            BuildUi();
            ApplySettings(SettingsStore.Load());
            pollTimer = new Timer();
            pollTimer.Interval = 2000;
            pollTimer.Tick += async delegate { await PollAsync(); };
            Shown += async delegate
            {
                WriteLog("Application started.");
                await RefreshRepoInfoAsync();
                if (autoStartBox.Checked) StartWatching();
            };
            FormClosing += delegate { SaveFromUi(false); };
        }

        private async Task CheckSetupAsync()
        {
            if (!ValidateRepo(true)) return;
            string repo = repoBox.Text.Trim();
            RunResult version = await git.RunAsync("git.exe", "--version", repo);
            if (!version.Success) { Fail("Git is not available: " + version.Output); return; }
            RunResult branch = await git.GitAsync(repo, "branch --show-current");
            RunResult remote = await git.GitAsync(repo, "remote get-url origin");
            if (!branch.Success || string.IsNullOrWhiteSpace(branch.Output)) { Fail("Could not read current branch."); return; }
            if (!remote.Success || string.IsNullOrWhiteSpace(remote.Output)) { Fail("Remote 'origin' is missing."); return; }
            branchValue.Text = branch.Output.Trim();
            SetStatus("Ready", good);
            WriteLog("Setup OK | " + version.Output.Trim() + " | branch=" + branch.Output.Trim() + " | origin=" + remote.Output.Trim());
        }

        private void StartWatching()
        {
            if (!ValidateRepo(true)) return;
            SaveFromUi(false);
            watching = true;
            pollTimer.Start();
            startButton.Enabled = false;
            stopButton.Enabled = true;
            SetStatus("Watching", good);
            WriteLog("AutoSync started.");
        }

        private void StopWatching()
        {
            watching = false;
            pollTimer.Stop();
            startButton.Enabled = true;
            stopButton.Enabled = false;
            SetStatus("Stopped", muted);
            WriteLog("AutoSync stopped.");
        }

        private async Task PollAsync()
        {
            if (!watching || busy || !ValidateRepo(false)) return;
            string repo = repoBox.Text.Trim();
            RunResult status = await git.GitAsync(repo, "status --porcelain");
            if (!status.Success) return;
            changesValue.Text = GitService.CountStatusLines(status.Output).ToString();

            if (string.IsNullOrWhiteSpace(status.Output))
            {
                lastFingerprint = "";
                changedAt = DateTime.MinValue;
                return;
            }
            if (status.Output != lastFingerprint)
            {
                lastFingerprint = status.Output;
                changedAt = DateTime.Now;
                return;
            }
            if (changedAt != DateTime.MinValue && (DateTime.Now - changedAt).TotalSeconds >= (double)delayBox.Value)
            {
                changedAt = DateTime.MaxValue;
                await SyncAsync("Auto");
            }
        }

        private async Task SyncAsync(string trigger)
        {
            if (busy) { WriteLog("Sync skipped: another sync is already running."); return; }
            if (!ValidateRepo(true)) return;
            busy = true;
            syncButton.Enabled = false;
            SetStatus("Syncing", accent);
            WriteLog(trigger + " sync started.");
            try
            {
                string repo = repoBox.Text.Trim();
                string expected = string.IsNullOrWhiteSpace(branchBox.Text) ? "main" : branchBox.Text.Trim();
                RunResult branch = await git.GitAsync(repo, "branch --show-current");
                if (!branch.Success || !string.Equals(branch.Output.Trim(), expected, StringComparison.OrdinalIgnoreCase))
                {
                    Fail("Branch mismatch. Current=" + branch.Output.Trim() + ", expected=" + expected);
                    return;
                }

                RunResult status = await git.GitAsync(repo, "status --porcelain");
                if (!status.Success) { Fail("git status failed: " + status.Output); return; }
                if (string.IsNullOrWhiteSpace(status.Output))
                {
                    WriteLog("No file changes. Trying push for pending local commits.");
                    await PushAsync(repo, expected);
                    return;
                }

                if (buildGateBox.Checked)
                {
                    string command = buildBox.Text.Trim();
                    if (command.Length == 0) { Fail("Build gate is enabled but command is empty."); return; }
                    WriteLog("Running build/test gate...");
                    RunResult build = await git.CommandAsync(repo, command);
                    if (!build.Success)
                    {
                        Fail("Build/test failed. Nothing was committed. | " + GitService.Tail(build.Output, 1400));
                        return;
                    }
                    WriteLog("Build/test passed.");
                }

                RunResult add = await git.GitAsync(repo, "add -A");
                if (!add.Success) { Fail("git add failed: " + add.Output); return; }
                string message = (string.IsNullOrWhiteSpace(prefixBox.Text) ? AppName : prefixBox.Text.Trim()) + " - " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
                RunResult commit = await git.GitAsync(repo, "commit -m " + GitService.Quote(message));
                if (!commit.Success) { Fail("git commit failed: " + commit.Output); return; }
                WriteLog("Commit created: " + message);
                await PushAsync(repo, expected);
            }
            catch (Exception ex)
            {
                AppPaths.WriteStartupError("Sync: " + ex);
                Fail("Unexpected sync error: " + ex.Message);
            }
            finally
            {
                busy = false;
                syncButton.Enabled = true;
                await RefreshRepoInfoAsync();
            }
        }

        private async Task PushAsync(string repo, string branch)
        {
            WriteLog("Pushing origin/" + branch + "...");
            RunResult push = await git.GitAsync(repo, "push origin " + GitService.Quote(branch));
            if (!push.Success)
            {
                Fail("Push failed. Local commit was kept; force-push was NOT used. | " + GitService.Tail(push.Output, 1400));
                return;
            }
            lastFingerprint = "";
            changedAt = DateTime.MinValue;
            lastSyncValue.Text = DateTime.Now.ToString("HH:mm:ss");
            SetStatus(watching ? "Watching" : "Synced", good);
            WriteLog("GitHub updated successfully.");
        }

        private async Task RefreshRepoInfoAsync()
        {
            if (!ValidateRepo(false)) { changesValue.Text = "-"; branchValue.Text = "-"; return; }
            string repo = repoBox.Text.Trim();
            RunResult status = await git.GitAsync(repo, "status --porcelain");
            RunResult branch = await git.GitAsync(repo, "branch --show-current");
            if (status.Success) changesValue.Text = GitService.CountStatusLines(status.Output).ToString();
            if (branch.Success) branchValue.Text = branch.Output.Trim();
        }

        private bool ValidateRepo(bool popup)
        {
            string path = repoBox.Text.Trim();
            string error = null;
            if (path.Length == 0) error = "Select a repository folder.";
            else if (!Directory.Exists(path)) error = "Repository folder does not exist.";
            else if (!Directory.Exists(Path.Combine(path, ".git"))) error = "Selected folder is not a Git repository (.git missing).";
            if (error == null) return true;
            if (popup) MessageBox.Show(this, error, AppName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            SetStatus("Setup error", bad);
            return false;
        }

        private void ApplySettings(AppSettings s)
        {
            repoBox.Text = s.RepoPath;
            branchBox.Text = string.IsNullOrWhiteSpace(s.Branch) ? "main" : s.Branch;
            delayBox.Value = Math.Max(delayBox.Minimum, Math.Min(delayBox.Maximum, s.DelaySeconds));
            buildGateBox.Checked = s.BuildGate;
            buildBox.Text = s.BuildCommand;
            prefixBox.Text = s.CommitPrefix;
            startupBox.Checked = s.StartWithWindows;
            autoStartBox.Checked = s.AutoStart;
        }

        private void SaveFromUi(bool notify)
        {
            try
            {
                AppSettings s = new AppSettings();
                s.RepoPath = repoBox.Text.Trim();
                s.Branch = branchBox.Text.Trim();
                s.DelaySeconds = (int)delayBox.Value;
                s.BuildGate = buildGateBox.Checked;
                s.BuildCommand = buildBox.Text;
                s.CommitPrefix = prefixBox.Text;
                s.StartWithWindows = startupBox.Checked;
                s.AutoStart = autoStartBox.Checked;
                SettingsStore.Save(s, Application.ExecutablePath);
                if (notify) WriteLog("Settings saved.");
            }
            catch (Exception ex) { Fail("Could not save settings: " + ex.Message); }
        }

        private void SetStatus(string text, System.Drawing.Color color)
        {
            statusValue.Text = text;
            statusValue.ForeColor = color;
        }

        private void Fail(string text)
        {
            WriteLog("ERROR: " + text);
            SetStatus("Error", bad);
        }

        private void WriteLog(string text)
        {
            string line = "[" + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "] " + text;
            AppPaths.AppendLog(line);
            if (logBox != null && !logBox.IsDisposed)
            {
                logBox.AppendText(line + Environment.NewLine);
                logBox.SelectionStart = logBox.TextLength;
                logBox.ScrollToCaret();
            }
        }
    }
}