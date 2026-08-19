using System;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

namespace SkyBitAutoSync
{
    internal sealed partial class MainForm : Form
    {
        private const string AppName = "SkyBit AutoSync";
        private const string Version = "2.0.0";
        private readonly Color bg = Color.FromArgb(16, 19, 26);
        private readonly Color card = Color.FromArgb(24, 29, 39);
        private readonly Color field = Color.FromArgb(31, 37, 49);
        private readonly Color fg = Color.FromArgb(238, 242, 248);
        private readonly Color muted = Color.FromArgb(154, 166, 184);
        private readonly Color accent = Color.FromArgb(70, 133, 255);
        private readonly Color good = Color.FromArgb(64, 194, 127);
        private readonly Color bad = Color.FromArgb(239, 90, 90);

        private TextBox repoBox, branchBox, buildBox, prefixBox, logBox;
        private NumericUpDown delayBox;
        private CheckBox buildGateBox, startupBox, autoStartBox;
        private Label statusValue, changesValue, branchValue, lastSyncValue;
        private Button startButton, stopButton, syncButton;

        private void BuildUi()
        {
            Text = AppName + " v" + Version;
            StartPosition = FormStartPosition.CenterScreen;
            Size = new Size(1040, 760);
            MinimumSize = new Size(900, 680);
            BackColor = bg;
            ForeColor = fg;
            Font = new Font("Segoe UI", 9F);
            AutoScaleMode = AutoScaleMode.Dpi;

            Panel header = new Panel { Dock = DockStyle.Top, Height = 84, BackColor = bg };
            Controls.Add(header);
            header.Controls.Add(new Label { Text = AppName, AutoSize = true, Font = new Font("Segoe UI Semibold", 22F, FontStyle.Bold), ForeColor = fg, Location = new Point(24, 12) });
            header.Controls.Add(new Label { Text = "Safe automatic Git commit + push for SkyBit", AutoSize = true, ForeColor = muted, Location = new Point(27, 56) });

            TableLayoutPanel root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(20, 0, 20, 18), ColumnCount = 1, RowCount = 4, BackColor = bg };
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 150));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 88));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 205));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            Controls.Add(root);
            root.BringToFront();

            Panel repoCard = Card(); root.Controls.Add(repoCard, 0, 0);
            repoCard.Controls.Add(LabelAt("REPOSITORY", 18, 14, true));
            repoBox = TextAt(18, 40, 720); repoBox.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right; repoCard.Controls.Add(repoBox);
            Button browse = ButtonAt("Browse", 750, 39, 86, false); browse.Anchor = AnchorStyles.Top | AnchorStyles.Right; browse.Click += BrowseClick; repoCard.Controls.Add(browse);
            Button check = ButtonAt("Check setup", 844, 39, 120, true); check.Anchor = AnchorStyles.Top | AnchorStyles.Right; check.Click += async delegate { await CheckSetupAsync(); }; repoCard.Controls.Add(check);
            repoCard.Controls.Add(LabelAt("Branch", 18, 82, false));
            branchBox = TextAt(18, 103, 165); repoCard.Controls.Add(branchBox);
            repoCard.Controls.Add(LabelAt("Idle delay", 202, 82, false));
            delayBox = new NumericUpDown { Location = new Point(202, 103), Size = new Size(105, 28), Minimum = 5, Maximum = 600, Value = 20, BackColor = field, ForeColor = fg, BorderStyle = BorderStyle.FixedSingle }; repoCard.Controls.Add(delayBox);
            repoCard.Controls.Add(LabelAt("seconds after last detected change", 315, 108, false));

            TableLayoutPanel stats = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 4, RowCount = 1, BackColor = bg, Padding = new Padding(0, 8, 0, 6) };
            for (int i = 0; i < 4; i++) stats.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 25));
            root.Controls.Add(stats, 0, 1);
            statusValue = Stat(stats, "STATUS", "Stopped", 0);
            changesValue = Stat(stats, "CHANGES", "-", 1);
            branchValue = Stat(stats, "BRANCH", "-", 2);
            lastSyncValue = Stat(stats, "LAST SYNC", "Never", 3);

            Panel settingsCard = Card(); root.Controls.Add(settingsCard, 0, 2);
            settingsCard.Controls.Add(LabelAt("SYNC SETTINGS", 18, 14, true));
            settingsCard.Controls.Add(LabelAt("Commit prefix", 18, 43, false));
            prefixBox = TextAt(18, 64, 290); settingsCard.Controls.Add(prefixBox);
            buildGateBox = new CheckBox { Text = "Require successful build before commit", Location = new Point(330, 62), Size = new Size(300, 28), ForeColor = fg, BackColor = card }; settingsCard.Controls.Add(buildGateBox);
            settingsCard.Controls.Add(LabelAt("Build / test command", 18, 101, false));
            buildBox = TextAt(18, 122, 946); buildBox.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right; settingsCard.Controls.Add(buildBox);
            startupBox = new CheckBox { Text = "Start with Windows", Location = new Point(18, 162), Size = new Size(165, 28), ForeColor = fg, BackColor = card }; settingsCard.Controls.Add(startupBox);
            autoStartBox = new CheckBox { Text = "Start AutoSync when app opens", Location = new Point(195, 162), Size = new Size(240, 28), ForeColor = fg, BackColor = card }; settingsCard.Controls.Add(autoStartBox);
            Button save = ButtonAt("Save settings", 818, 158, 146, false); save.Anchor = AnchorStyles.Top | AnchorStyles.Right; save.Click += delegate { SaveFromUi(true); }; settingsCard.Controls.Add(save);

            Panel bottom = new Panel { Dock = DockStyle.Fill, BackColor = bg, Padding = new Padding(0, 8, 0, 0) }; root.Controls.Add(bottom, 0, 3);
            Panel actions = new Panel { Dock = DockStyle.Top, Height = 45, BackColor = bg }; bottom.Controls.Add(actions);
            startButton = ButtonAt("Start AutoSync", 0, 4, 145, true); startButton.Click += delegate { StartWatching(); }; actions.Controls.Add(startButton);
            stopButton = ButtonAt("Stop", 155, 4, 95, false); stopButton.Enabled = false; stopButton.Click += delegate { StopWatching(); }; actions.Controls.Add(stopButton);
            syncButton = ButtonAt("Sync now", 260, 4, 110, false); syncButton.Click += async delegate { await SyncAsync("Manual"); }; actions.Controls.Add(syncButton);
            Button openLogs = ButtonAt("Open logs", 380, 4, 110, false); openLogs.Click += delegate { Process.Start("explorer.exe", AppPaths.DataDir); }; actions.Controls.Add(openLogs);
            logBox = new TextBox { Dock = DockStyle.Fill, Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, BackColor = Color.FromArgb(12, 15, 20), ForeColor = Color.FromArgb(190, 204, 222), BorderStyle = BorderStyle.FixedSingle, Font = new Font("Consolas", 9F) };
            bottom.Controls.Add(logBox); logBox.BringToFront();
        }

        private Panel Card() { return new Panel { Dock = DockStyle.Fill, Margin = new Padding(0, 5, 0, 5), BackColor = card }; }
        private Label LabelAt(string text, int x, int y, bool strong) { return new Label { Text = text, AutoSize = true, Location = new Point(x, y), ForeColor = strong ? fg : muted, Font = strong ? new Font("Segoe UI Semibold", 9F, FontStyle.Bold) : Font }; }
        private TextBox TextAt(int x, int y, int width) { return new TextBox { Location = new Point(x, y), Size = new Size(width, 28), BackColor = field, ForeColor = fg, BorderStyle = BorderStyle.FixedSingle }; }
        private Button ButtonAt(string text, int x, int y, int width, bool primary)
        {
            Button b = new Button { Text = text, Location = new Point(x, y), Size = new Size(width, 32), FlatStyle = FlatStyle.Flat, BackColor = primary ? accent : field, ForeColor = Color.White, Cursor = Cursors.Hand };
            b.FlatAppearance.BorderSize = 0;
            return b;
        }
        private Label Stat(TableLayoutPanel parent, string caption, string value, int column)
        {
            Panel p = new Panel { Dock = DockStyle.Fill, Margin = new Padding(column == 0 ? 0 : 5, 0, column == 3 ? 0 : 5, 0), BackColor = card };
            p.Controls.Add(new Label { Text = caption, AutoSize = true, Location = new Point(13, 9), ForeColor = muted, Font = new Font("Segoe UI Semibold", 8F) });
            Label v = new Label { Text = value, AutoSize = true, Location = new Point(13, 31), ForeColor = fg, Font = new Font("Segoe UI Semibold", 12F, FontStyle.Bold) };
            p.Controls.Add(v); parent.Controls.Add(p, column, 0); return v;
        }

        private void BrowseClick(object sender, EventArgs e)
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Select SkyBit Git repository";
                if (dialog.ShowDialog(this) == DialogResult.OK) repoBox.Text = dialog.SelectedPath;
            }
        }
    }
}