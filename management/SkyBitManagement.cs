using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.IO.Compression;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using IOPath = System.IO.Path;

namespace SkyBitManagement
{
    internal sealed class ServiceDefinition
    {
        public string Id;
        public string Name;
        public string Subtitle;
        public string RelativePath;
        public string Jar;
        public string MinMemory;
        public string MaxMemory;
        public int Port;
        public string StopCommand;
        public string Accent;

        public static List<ServiceDefinition> All()
        {
            return new List<ServiceDefinition>
            {
                new ServiceDefinition { Id="lobby", Name="Lobby", Subtitle="Vstupná brána siete", RelativePath="servers\\lobby", Jar="server.jar", MinMemory="1G", MaxMemory="2G", Port=25566, StopCommand="stop", Accent="#20E3F0" },
                new ServiceDefinition { Id="skybit", Name="Survival", Subtitle="Slimefun & Economy", RelativePath="servers\\skybit", Jar="server.jar", MinMemory="2G", MaxMemory="6G", Port=25567, StopCommand="stop", Accent="#67F5A2" },
                new ServiceDefinition { Id="skyblock", Name="SkyBlock", Subtitle="Islands & Challenges", RelativePath="servers\\skyblock", Jar="server.jar", MinMemory="2G", MaxMemory="4G", Port=25568, StopCommand="stop", Accent="#A66CFF" },
                new ServiceDefinition { Id="velocity", Name="Velocity", Subtitle="Network proxy", RelativePath="proxy", Jar="velocity.jar", MinMemory="256M", MaxMemory="1G", Port=25565, StopCommand="shutdown", Accent="#FFB85C" }
            };
        }
    }

    internal sealed class RuntimeSnapshot
    {
        public bool Running;
        public bool Managed;
        public int ProcessId;
        public double CpuPercent;
        public long MemoryBytes;
        public TimeSpan Uptime;
        public int Players = -1;
        public int MaxPlayers = -1;
    }

    internal static class Paths
    {
        public static string Root()
        {
            string directory = IOPath.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
            return Directory.GetParent(directory).FullName;
        }

        public static string Pid(string root, string id) { return IOPath.Combine(root, "runtime", "pids", id + ".pid"); }
        public static string HostPid(string root, string id) { return IOPath.Combine(root, "runtime", "pids", id + ".host.pid"); }
        public static string Pipe(string id) { return "SkyBitManagement_" + id; }
    }

    internal static class ProcessTools
    {
        public static int ReadLivePid(string file)
        {
            try
            {
                int pid;
                if (!File.Exists(file) || !Int32.TryParse(File.ReadAllText(file).Trim(), out pid)) return 0;
                Process process = Process.GetProcessById(pid);
                return process.HasExited ? 0 : pid;
            }
            catch { return 0; }
        }

        public static bool SendCommand(string serviceId, string command, int timeout)
        {
            try
            {
                using (NamedPipeClientStream pipe = new NamedPipeClientStream(".", Paths.Pipe(serviceId), PipeDirection.Out))
                {
                    pipe.Connect(timeout);
                    using (StreamWriter writer = new StreamWriter(pipe, new UTF8Encoding(false)))
                    {
                        writer.AutoFlush = true;
                        writer.WriteLine(command);
                    }
                }
                return true;
            }
            catch { return false; }
        }
    }

    internal static class ServiceHost
    {
        private static readonly object MainLogLock = new object();
        private static readonly object ErrorLogLock = new object();

        public static int Run(string root, ServiceDefinition definition)
        {
            string pidFile = Paths.Pid(root, definition.Id);
            if (ProcessTools.ReadLivePid(pidFile) != 0) return 3;

            string java = IOPath.Combine(root, "runtime", "java-25", "bin", "java.exe");
            string work = IOPath.Combine(root, definition.RelativePath);
            string jar = IOPath.Combine(work, definition.Jar);
            if (!File.Exists(java) || !File.Exists(jar)) return 4;

            Directory.CreateDirectory(IOPath.GetDirectoryName(pidFile));
            string mainLog = IOPath.Combine(work, "console.log");
            string errorLog = IOPath.Combine(work, "console-error.log");
            RotateIfLarge(mainLog);
            RotateIfLarge(errorLog);

            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = java;
            start.WorkingDirectory = work;
            start.Arguments = String.Format("-Xms{0} -Xmx{1} -XX:+UseG1GC -jar \"{2}\"{3}", definition.MinMemory, definition.MaxMemory, definition.Jar, definition.Id == "velocity" ? "" : " --nogui");
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            start.RedirectStandardInput = true;
            start.RedirectStandardOutput = true;
            start.RedirectStandardError = true;
            start.StandardOutputEncoding = Encoding.UTF8;
            start.StandardErrorEncoding = Encoding.UTF8;

            Process javaProcess = new Process();
            javaProcess.StartInfo = start;
            javaProcess.EnableRaisingEvents = true;
            javaProcess.OutputDataReceived += delegate(object sender, DataReceivedEventArgs args) { if (args.Data != null) Append(mainLog, args.Data, MainLogLock); };
            javaProcess.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs args) { if (args.Data != null) Append(errorLog, args.Data, ErrorLogLock); };

            try
            {
                Append(mainLog, "=== SkyBit Management: starting " + definition.Name + " at " + DateTime.Now.ToString("s") + " ===", MainLogLock);
                javaProcess.Start();
                File.WriteAllText(pidFile, javaProcess.Id.ToString(), Encoding.ASCII);
                File.WriteAllText(Paths.HostPid(root, definition.Id), Process.GetCurrentProcess().Id.ToString(), Encoding.ASCII);
                javaProcess.BeginOutputReadLine();
                javaProcess.BeginErrorReadLine();
                StartPipe(javaProcess, definition);
                javaProcess.WaitForExit();
                Append(mainLog, "=== SkyBit Management: " + definition.Name + " stopped with code " + javaProcess.ExitCode + " ===", MainLogLock);
                DeleteIfMatches(pidFile, javaProcess.Id);
                DeleteIfMatches(Paths.HostPid(root, definition.Id), Process.GetCurrentProcess().Id);
                return javaProcess.ExitCode;
            }
            catch (Exception exception)
            {
                Append(errorLog, "SkyBit Management host error: " + exception, ErrorLogLock);
                DeleteIfMatches(Paths.HostPid(root, definition.Id), Process.GetCurrentProcess().Id);
                return 5;
            }
        }

        private static void StartPipe(Process javaProcess, ServiceDefinition definition)
        {
            Thread thread = new Thread(delegate()
            {
                while (!javaProcess.HasExited)
                {
                    try
                    {
                        using (NamedPipeServerStream server = new NamedPipeServerStream(Paths.Pipe(definition.Id), PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.None))
                        {
                            server.WaitForConnection();
                            using (StreamReader reader = new StreamReader(server, Encoding.UTF8))
                            {
                                string command = reader.ReadLine();
                                if (!String.IsNullOrWhiteSpace(command) && !javaProcess.HasExited)
                                {
                                    javaProcess.StandardInput.WriteLine(command);
                                    javaProcess.StandardInput.Flush();
                                }
                            }
                        }
                    }
                    catch { Thread.Sleep(250); }
                }
            });
            thread.IsBackground = true;
            thread.Name = "SkyBit control pipe " + definition.Id;
            thread.Start();
        }

        private static void Append(string file, string line, object sync)
        {
            lock (sync)
            {
                using (FileStream stream = new FileStream(file, FileMode.Append, FileAccess.Write, FileShare.ReadWrite))
                using (StreamWriter writer = new StreamWriter(stream, new UTF8Encoding(false))) writer.WriteLine(line);
            }
        }

        private static void RotateIfLarge(string file)
        {
            try
            {
                if (File.Exists(file) && new FileInfo(file).Length > 16L * 1024L * 1024L)
                {
                    string archived = file + ".1";
                    if (File.Exists(archived)) File.Delete(archived);
                    File.Move(file, archived);
                }
            }
            catch { }
        }

        private static void DeleteIfMatches(string file, int expected)
        {
            try
            {
                int current;
                if (File.Exists(file) && Int32.TryParse(File.ReadAllText(file).Trim(), out current) && current == expected) File.Delete(file);
            }
            catch { }
        }
    }

    internal sealed class ServiceCard
    {
        public ServiceDefinition Definition;
        public Border Border;
        public Ellipse Dot;
        public TextBlock Status;
        public TextBlock Players;
        public TextBlock Memory;
        public TextBlock Cpu;
        public TextBlock Uptime;
        public TextBlock Process;
        public Button Start;
        public Button Stop;
        public Button Restart;
    }

    internal sealed class MainWindow : Window
    {
        private readonly string root;
        private readonly List<ServiceDefinition> definitions;
        private readonly Dictionary<string, ServiceCard> cards = new Dictionary<string, ServiceCard>();
        private readonly Dictionary<string, RuntimeSnapshot> snapshots = new Dictionary<string, RuntimeSnapshot>();
        private readonly Dictionary<int, TimeSpan> previousCpu = new Dictionary<int, TimeSpan>();
        private readonly Dictionary<int, DateTime> previousCpuAt = new Dictionary<int, DateTime>();
        private readonly Dictionary<string, int[]> pingValues = new Dictionary<string, int[]>();
        private readonly Dictionary<string, bool> previousRunning = new Dictionary<string, bool>();
        private readonly Dictionary<string, int> highCpuSamples = new Dictionary<string, int>();
        private readonly HashSet<string> requestedStops = new HashSet<string>();
        private readonly List<string> alerts = new List<string>();
        private readonly DispatcherTimer timer;
        private ServiceDefinition selected;
        private TextBlock networkStatus;
        private TextBlock networkPlayers;
        private TextBlock selectedLabel;
        private TextBox console;
        private TextBox command;
        private Button sendButton;
        private TextBox consoleFilter;
        private TextBlock alertCount;
        private TextBlock alertLatest;
        private string logMode = "console";
        private bool pingBusy;
        private bool stateInitialized;
        private string lastLogIdentity = "";

        public MainWindow(string rootPath)
        {
            root = rootPath;
            definitions = ServiceDefinition.All();
            selected = definitions[0];
            Title = "SkyBit Management";
            Width = 1440;
            Height = 900;
            MinWidth = 1120;
            MinHeight = 720;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = Brush("#05080D");
            Foreground = Brushes.White;
            FontFamily = new FontFamily("Segoe UI");
            Content = BuildLayout();

            timer = new DispatcherTimer();
            timer.Interval = TimeSpan.FromSeconds(2);
            timer.Tick += delegate { RefreshAll(); };
            Loaded += delegate { RefreshAll(); timer.Start(); };
            Closed += delegate { timer.Stop(); };
        }

        private UIElement BuildLayout()
        {
            Grid page = new Grid();
            page.RowDefinitions.Add(new RowDefinition { Height = new GridLength(84) });
            page.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            page.Children.Add(BuildHeader());
            Grid body = new Grid();
            body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(245) });
            body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            Grid.SetRow(body, 1);
            body.Children.Add(BuildSidebar());
            UIElement content = BuildContent();
            Grid.SetColumn(content, 1);
            body.Children.Add(content);
            page.Children.Add(body);
            return page;
        }

        private UIElement BuildHeader()
        {
            Border header = new Border { Background = Brush("#080D14"), BorderBrush = Brush("#1B2633"), BorderThickness = new Thickness(0, 0, 0, 1), Padding = new Thickness(28, 0, 28, 0) };
            Grid grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            StackPanel brand = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            string icon = IOPath.Combine(root, "assets", "skybit-server-icon.png");
            if (File.Exists(icon)) brand.Children.Add(new Image { Source = new BitmapImage(new Uri(icon)), Width = 45, Height = 45, Margin = new Thickness(0, 0, 14, 0) });
            StackPanel words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            words.Children.Add(Text("SKYBIT", 18, FontWeights.Black, "#FFFFFF"));
            TextBlock sub = Text("MANAGEMENT CONSOLE", 9, FontWeights.Bold, "#20E3F0");
            words.Children.Add(sub);
            brand.Children.Add(words);
            grid.Children.Add(brand);

            StackPanel health = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Right };
            Ellipse dot = new Ellipse { Width = 8, Height = 8, Fill = Brush("#67F5A2"), Margin = new Thickness(0, 0, 10, 0) };
            health.Children.Add(dot);
            networkStatus = Text("NAČÍTAVAM SIEŤ", 10, FontWeights.Bold, "#AAB6C5");
            health.Children.Add(networkStatus);
            networkPlayers = Text("— hráčov", 12, FontWeights.Bold, "#FFFFFF");
            networkPlayers.Margin = new Thickness(28, 0, 0, 0);
            health.Children.Add(networkPlayers);
            Grid.SetColumn(health, 2);
            grid.Children.Add(health);
            header.Child = grid;
            return header;
        }

        private UIElement BuildSidebar()
        {
            Border sidebar = new Border { Background = Brush("#070B11"), BorderBrush = Brush("#18222E"), BorderThickness = new Thickness(0, 0, 1, 0), Padding = new Thickness(22) };
            DockPanel dock = new DockPanel();
            StackPanel top = new StackPanel();
            TextBlock nav = Text("NETWORK CONTROL", 9, FontWeights.Bold, "#667588"); nav.Margin = new Thickness(4, 4, 0, 18); top.Children.Add(nav);
            Button startAll = ActionButton("▶  SPUSTIŤ VŠETKO", "#20E3F0", "#07141A");
            startAll.Margin = new Thickness(0, 0, 0, 10);
            startAll.Click += delegate { StartAll(); };
            top.Children.Add(startAll);
            Button stopAll = OutlineButton("■  ZASTAVIŤ SPRAVOVANÉ");
            stopAll.Click += delegate { StopAll(); };
            top.Children.Add(stopAll);
            Border divider = new Border { BorderBrush = Brush("#18222E"), BorderThickness = new Thickness(0, 0, 0, 1), Margin = new Thickness(0, 26, 0, 22) };
            top.Children.Add(divider);
            TextBlock servers = Text("SERVERY", 9, FontWeights.Bold, "#667588"); servers.Margin = new Thickness(4, 0, 0, 10); top.Children.Add(servers);
            foreach (ServiceDefinition definition in definitions)
            {
                Button item = OutlineButton(definition.Name.ToUpperInvariant() + "   :" + definition.Port);
                item.HorizontalContentAlignment = HorizontalAlignment.Left;
                item.Margin = new Thickness(0, 0, 0, 7);
                ServiceDefinition captured = definition;
                item.Click += delegate { SelectService(captured); };
                top.Children.Add(item);
            }
            StackPanel footer = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom };
            DockPanel.SetDock(footer, Dock.Bottom);
            Border info = new Border { Background = Brush("#0C131D"), BorderBrush = Brush("#1B2A39"), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(4), Padding = new Thickness(14) };
            StackPanel infoStack = new StackPanel();
            infoStack.Children.Add(Text("LOCAL MODE", 9, FontWeights.Bold, "#67F5A2"));
            TextBlock infoText = Text("Riadenie je dostupné iba na tomto počítači. Žiadne porty panela nie sú verejné.", 11, FontWeights.Normal, "#8391A3");
            infoText.TextWrapping = TextWrapping.Wrap; infoText.Margin = new Thickness(0, 8, 0, 0); infoStack.Children.Add(infoText);
            info.Child = infoStack; footer.Children.Add(info); dock.Children.Add(footer); dock.Children.Add(top);
            sidebar.Child = dock;
            return sidebar;
        }

        private UIElement BuildContent()
        {
            ScrollViewer scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
            StackPanel panel = new StackPanel { Margin = new Thickness(30, 26, 30, 30) };
            Grid heading = new Grid(); heading.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); heading.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            StackPanel headingText = new StackPanel(); headingText.Children.Add(Text("Prehľad siete", 30, FontWeights.Bold, "#FFFFFF")); headingText.Children.Add(Text("Procesy, výkon a bezpečné lokálne ovládanie všetkých SkyBit serverov.", 12, FontWeights.Normal, "#8391A3")); heading.Children.Add(headingText);
            Button openRoot = OutlineButton("OTVORIŤ PRIEČINOK SIETE"); openRoot.VerticalAlignment = VerticalAlignment.Center; openRoot.Click += delegate { OpenFolder(root); }; Grid.SetColumn(openRoot, 1); heading.Children.Add(openRoot);
            panel.Children.Add(heading);

            UniformGrid cardGrid = new UniformGrid { Columns = 2, Margin = new Thickness(0, 24, 0, 22) };
            foreach (ServiceDefinition definition in definitions) cardGrid.Children.Add(BuildCard(definition));
            panel.Children.Add(cardGrid);
            panel.Children.Add(BuildOperations());
            panel.Children.Add(BuildConsole());
            scroll.Content = panel;
            return scroll;
        }

        private UIElement BuildCard(ServiceDefinition definition)
        {
            Border border = new Border { Background = Brush("#0A1019"), BorderBrush = Brush("#1B2836"), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(5), Margin = new Thickness(0, 0, 14, 14), Padding = new Thickness(22), Cursor = Cursors.Hand };
            Grid grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(68) });
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            Grid top = new Grid(); top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            StackPanel identity = new StackPanel(); identity.Children.Add(Text(definition.Name, 21, FontWeights.Bold, "#FFFFFF")); identity.Children.Add(Text(definition.Subtitle + "  •  :" + definition.Port, 10, FontWeights.Normal, "#8391A3")); top.Children.Add(identity);
            StackPanel state = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Top };
            Ellipse dot = new Ellipse { Width = 8, Height = 8, Fill = Brush("#506070"), Margin = new Thickness(0, 5, 8, 0) }; state.Children.Add(dot);
            TextBlock status = Text("OFFLINE", 9, FontWeights.Bold, "#8391A3"); state.Children.Add(status); Grid.SetColumn(state, 1); top.Children.Add(state); grid.Children.Add(top);

            Grid metrics = new Grid { Margin = new Thickness(0, 18, 0, 12) };
            for (int i=0; i<4; i++) metrics.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            TextBlock players, memory, cpu, uptime;
            metrics.Children.Add(Metric("HRÁČI", out players, 0)); metrics.Children.Add(Metric("RAM", out memory, 1)); metrics.Children.Add(Metric("CPU", out cpu, 2)); metrics.Children.Add(Metric("UPTIME", out uptime, 3));
            Grid.SetRow(metrics, 1); grid.Children.Add(metrics);

            Grid actions = new Grid(); actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); actions.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Button start = SmallButton("SPUSTIŤ", definition.Accent); start.Click += delegate { StartService(definition); }; actions.Children.Add(start);
            Button stop = SmallButton("STOP", "#FF6878"); stop.Margin = new Thickness(8, 0, 0, 0); stop.Click += delegate { StopService(definition, false); }; Grid.SetColumn(stop, 1); actions.Children.Add(stop);
            Button restart = SmallButton("REŠTART", "#FFB85C"); restart.Margin = new Thickness(8, 0, 0, 0); restart.Click += delegate { RestartService(definition); }; Grid.SetColumn(restart, 2); actions.Children.Add(restart);
            TextBlock process = Text("PID —", 9, FontWeights.Normal, "#667588"); process.VerticalAlignment = VerticalAlignment.Center; process.HorizontalAlignment = HorizontalAlignment.Right; Grid.SetColumn(process, 4); actions.Children.Add(process);
            Grid.SetRow(actions, 2); grid.Children.Add(actions);
            border.Child = grid;
            border.MouseLeftButtonUp += delegate { SelectService(definition); };
            cards[definition.Id] = new ServiceCard { Definition=definition, Border=border, Dot=dot, Status=status, Players=players, Memory=memory, Cpu=cpu, Uptime=uptime, Process=process, Start=start, Stop=stop, Restart=restart };
            return border;
        }

        private UIElement BuildOperations()
        {
            Grid row = new Grid { Margin = new Thickness(0, 0, 14, 22) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            Border alertPanel = new Border { Background = Brush("#0A1019"), BorderBrush = Brush("#1B2836"), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(5), Padding = new Thickness(20), Margin = new Thickness(0, 0, 7, 0) };
            Grid alertGrid = new Grid(); alertGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); alertGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); alertGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Border badge = new Border { Width = 42, Height = 42, Background = Brush("#111C28"), BorderBrush = Brush("#294057"), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(21) };
            alertCount = Text("0", 16, FontWeights.Bold, "#67F5A2"); alertCount.HorizontalAlignment = HorizontalAlignment.Center; alertCount.VerticalAlignment = VerticalAlignment.Center; badge.Child = alertCount; alertGrid.Children.Add(badge);
            StackPanel alertCopy = new StackPanel { Margin = new Thickness(14, 1, 14, 0) }; alertCopy.Children.Add(Text("CENTRUM UPOZORNENÍ", 9, FontWeights.Bold, "#E9F1F8")); alertLatest = Text("Žiadne nové upozornenia.", 10, FontWeights.Normal, "#8391A3"); alertLatest.Margin = new Thickness(0, 7, 0, 0); alertLatest.TextTrimming = TextTrimming.CharacterEllipsis; alertCopy.Children.Add(alertLatest); Grid.SetColumn(alertCopy, 1); alertGrid.Children.Add(alertCopy);
            Button clearAlerts = SmallButton("VYČISTIŤ", "#8A9AAF"); clearAlerts.VerticalAlignment = VerticalAlignment.Center; clearAlerts.Click += delegate { alerts.Clear(); UpdateAlerts(); }; Grid.SetColumn(clearAlerts, 2); alertGrid.Children.Add(clearAlerts); alertPanel.Child = alertGrid; row.Children.Add(alertPanel);

            Border toolsPanel = new Border { Background = Brush("#0A1019"), BorderBrush = Brush("#1B2836"), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(5), Padding = new Thickness(20), Margin = new Thickness(7, 0, 0, 0) };
            Grid tools = new Grid(); tools.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); tools.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            StackPanel toolsCopy = new StackPanel(); toolsCopy.Children.Add(Text("ÚDRŽBA A RECOVERY", 9, FontWeights.Bold, "#E9F1F8")); toolsCopy.Children.Add(Text("Lokálne zálohy a diagnostika.", 10, FontWeights.Normal, "#8391A3")); tools.Children.Add(toolsCopy);
            StackPanel toolButtons = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            Button backup = SmallButton("CONFIG ZÁLOHA", "#67F5A2"); backup.Click += delegate { BackupConfiguration(); }; toolButtons.Children.Add(backup);
            Button diagnostics = SmallButton("DIAGNOSTIKA", "#20E3F0"); diagnostics.Margin = new Thickness(8, 0, 0, 0); diagnostics.Click += delegate { ExportDiagnostics(); }; toolButtons.Children.Add(diagnostics);
            Button openBackups = SmallButton("ZÁLOHY", "#A66CFF"); openBackups.Margin = new Thickness(8, 0, 0, 0); openBackups.Click += delegate { string path = IOPath.Combine(root, "backups", "manager"); Directory.CreateDirectory(path); OpenFolder(path); }; toolButtons.Children.Add(openBackups);
            Grid.SetColumn(toolButtons, 1); tools.Children.Add(toolButtons); toolsPanel.Child = tools; Grid.SetColumn(toolsPanel, 1); row.Children.Add(toolsPanel);
            return row;
        }

        private UIElement BuildConsole()
        {
            Border shell = new Border { Background = Brush("#080D14"), BorderBrush = Brush("#1B2836"), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(5) };
            Grid grid = new Grid(); grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(52) }); grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(265) }); grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(48) });
            Grid bar = new Grid { Background = Brush("#0B121B") }; bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            selectedLabel = Text("KONZOLA  /  LOBBY", 10, FontWeights.Bold, "#20E3F0"); selectedLabel.VerticalAlignment = VerticalAlignment.Center; selectedLabel.Margin = new Thickness(18, 0, 0, 0); bar.Children.Add(selectedLabel);
            StackPanel consoleActions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 12, 0) };
            Button consoleLog = SmallButton("CONSOLE", "#20E3F0"); consoleLog.Click += delegate { SetLogMode("console"); }; consoleActions.Children.Add(consoleLog);
            Button paperLog = SmallButton("PAPER LOG", "#67F5A2"); paperLog.Margin = new Thickness(6, 0, 0, 0); paperLog.Click += delegate { SetLogMode("latest"); }; consoleActions.Children.Add(paperLog);
            Button errorLog = SmallButton("ERRORS", "#FF6878"); errorLog.Margin = new Thickness(6, 0, 0, 0); errorLog.Click += delegate { SetLogMode("error"); }; consoleActions.Children.Add(errorLog);
            consoleFilter = new TextBox { Width = 145, Height = 28, Margin = new Thickness(8, 0, 0, 0), Background = Brush("#050A10"), Foreground = Brush("#C6D1DC"), BorderBrush = Brush("#263647"), BorderThickness = new Thickness(1), FontFamily = new FontFamily("Consolas"), FontSize = 10, Padding = new Thickness(8, 5, 8, 3), ToolTip = "Filtrovať zobrazené riadky" };
            consoleFilter.TextChanged += delegate { lastLogIdentity = ""; RefreshLog(); }; consoleActions.Children.Add(consoleFilter);
            Button open = SmallButton("PRIEČINOK", "#8A9AAF"); open.Click += delegate { OpenFolder(IOPath.Combine(root, selected.RelativePath)); }; consoleActions.Children.Add(open);
            open.Margin = new Thickness(8, 0, 0, 0);
            Grid.SetColumn(consoleActions, 1); bar.Children.Add(consoleActions); grid.Children.Add(bar);
            console = new TextBox { Background = Brush("#04070B"), Foreground = Brush("#AFC0D2"), BorderThickness = new Thickness(0), FontFamily = new FontFamily("Consolas"), FontSize = 11, IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.NoWrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(14) };
            Grid.SetRow(console, 1); grid.Children.Add(console);
            Grid input = new Grid { Background = Brush("#070C12") }; input.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); input.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(120) });
            command = new TextBox { Background = Brushes.Transparent, Foreground = Brushes.White, BorderThickness = new Thickness(0), FontFamily = new FontFamily("Consolas"), FontSize = 12, VerticalContentAlignment = VerticalAlignment.Center, Padding = new Thickness(15, 0, 10, 0), ToolTip = "Príkaz bez úvodného lomítka" };
            command.KeyDown += delegate(object sender, KeyEventArgs args) { if (args.Key == Key.Enter) { SendConsoleCommand(); args.Handled = true; } }; input.Children.Add(command);
            sendButton = ActionButton("ODOSLAŤ", "#20E3F0", "#061418"); sendButton.Click += delegate { SendConsoleCommand(); }; Grid.SetColumn(sendButton, 1); input.Children.Add(sendButton); Grid.SetRow(input, 2); grid.Children.Add(input);
            shell.Child = grid;
            return shell;
        }

        private UIElement Metric(string title, out TextBlock value, int column)
        {
            StackPanel stack = new StackPanel(); stack.Children.Add(Text(title, 8, FontWeights.Bold, "#5F6E80")); value = Text("—", 15, FontWeights.Bold, "#E9F1F8"); value.Margin = new Thickness(0, 5, 0, 0); stack.Children.Add(value); Grid.SetColumn(stack, column); return stack;
        }

        private void RefreshAll()
        {
            int running = 0;
            int totalPlayers = 0;
            foreach (ServiceDefinition definition in definitions)
            {
                RuntimeSnapshot snapshot = Capture(definition);
                snapshots[definition.Id] = snapshot;
                bool wasRunning;
                if (stateInitialized && previousRunning.TryGetValue(definition.Id, out wasRunning) && wasRunning != snapshot.Running)
                {
                    if (snapshot.Running) AddAlert("INFO", definition.Name + " je online.");
                    else if (requestedStops.Remove(definition.Id)) AddAlert("INFO", definition.Name + " bol bezpečne zastavený.");
                    else AddAlert("CRITICAL", definition.Name + " sa neočakávane zastavil.");
                }
                previousRunning[definition.Id] = snapshot.Running;

                int samples = 0;
                highCpuSamples.TryGetValue(definition.Id, out samples);
                if (snapshot.Running && snapshot.CpuPercent >= 90.0)
                {
                    samples++;
                    if (samples == 3) AddAlert("WARNING", definition.Name + " má CPU nad 90 %.");
                }
                else samples = 0;
                highCpuSamples[definition.Id] = samples;
                if (snapshot.Running) running++;
                if (snapshot.Players > 0 && definition.Id != "velocity") totalPlayers += snapshot.Players;
                UpdateCard(cards[definition.Id], snapshot);
            }
            stateInitialized = true;
            RuntimeSnapshot proxy;
            if (snapshots.TryGetValue("velocity", out proxy) && proxy.Players >= 0) totalPlayers = proxy.Players;
            networkStatus.Text = running == definitions.Count ? "SIEŤ JE ONLINE" : running == 0 ? "SIEŤ JE OFFLINE" : "ČIASTOČNÁ PREVÁDZKA";
            networkStatus.Foreground = Brush(running == definitions.Count ? "#67F5A2" : running == 0 ? "#FF6878" : "#FFB85C");
            networkPlayers.Text = totalPlayers + " hráčov";
            RefreshLog();
            QueuePings();
        }

        private RuntimeSnapshot Capture(ServiceDefinition definition)
        {
            RuntimeSnapshot snapshot = new RuntimeSnapshot();
            int pid = ProcessTools.ReadLivePid(Paths.Pid(root, definition.Id));
            snapshot.ProcessId = pid;
            snapshot.Running = pid != 0;
            snapshot.Managed = snapshot.Running && ProcessTools.ReadLivePid(Paths.HostPid(root, definition.Id)) != 0;
            int[] ping;
            if (pingValues.TryGetValue(definition.Id, out ping)) { snapshot.Players = ping[0]; snapshot.MaxPlayers = ping[1]; }
            if (!snapshot.Running) return snapshot;
            try
            {
                Process process = Process.GetProcessById(pid);
                snapshot.MemoryBytes = process.WorkingSet64;
                snapshot.Uptime = DateTime.Now - process.StartTime;
                DateTime now = DateTime.UtcNow;
                TimeSpan cpu = process.TotalProcessorTime;
                TimeSpan oldCpu; DateTime oldAt;
                if (previousCpu.TryGetValue(pid, out oldCpu) && previousCpuAt.TryGetValue(pid, out oldAt))
                {
                    double elapsed = (now - oldAt).TotalMilliseconds;
                    if (elapsed > 0) snapshot.CpuPercent = Math.Max(0, Math.Min(100, (cpu - oldCpu).TotalMilliseconds / elapsed / Environment.ProcessorCount * 100.0));
                }
                previousCpu[pid] = cpu; previousCpuAt[pid] = now;
            }
            catch { snapshot.Running = false; }
            return snapshot;
        }

        private void UpdateCard(ServiceCard card, RuntimeSnapshot snapshot)
        {
            card.Dot.Fill = Brush(snapshot.Running ? card.Definition.Accent : "#506070");
            card.Status.Text = snapshot.Running ? (snapshot.Managed ? "ONLINE • MANAGED" : "ONLINE • EXTERNAL") : "OFFLINE";
            card.Status.Foreground = Brush(snapshot.Running ? card.Definition.Accent : "#8391A3");
            card.Players.Text = snapshot.Players >= 0 ? String.Format("{0}/{1}", snapshot.Players, snapshot.MaxPlayers) : "—";
            card.Memory.Text = snapshot.Running ? FormatBytes(snapshot.MemoryBytes) : "—";
            card.Cpu.Text = snapshot.Running ? snapshot.CpuPercent.ToString("0.0") + "%" : "—";
            card.Uptime.Text = snapshot.Running ? FormatUptime(snapshot.Uptime) : "—";
            card.Process.Text = snapshot.Running ? "PID " + snapshot.ProcessId + (snapshot.Managed ? "  •  CONSOLE READY" : "  •  READ ONLY") : "PID —";
            card.Start.IsEnabled = !snapshot.Running;
            card.Stop.IsEnabled = snapshot.Managed;
            card.Restart.IsEnabled = snapshot.Managed;
            card.Border.BorderBrush = Brush(selected.Id == card.Definition.Id ? card.Definition.Accent : "#1B2836");
        }

        private void QueuePings()
        {
            if (pingBusy) return;
            pingBusy = true;
            ThreadPool.QueueUserWorkItem(delegate
            {
                Dictionary<string, int[]> values = new Dictionary<string, int[]>();
                foreach (ServiceDefinition definition in definitions)
                {
                    int[] result = Ping(definition.Port);
                    if (result != null) values[definition.Id] = result;
                }
                Dispatcher.BeginInvoke(new Action(delegate { foreach (KeyValuePair<string,int[]> pair in values) pingValues[pair.Key] = pair.Value; pingBusy = false; }));
            });
        }

        private static int[] Ping(int port)
        {
            try
            {
                using (TcpClient client = new TcpClient())
                {
                    IAsyncResult connect = client.BeginConnect("127.0.0.1", port, null, null);
                    if (!connect.AsyncWaitHandle.WaitOne(550)) return null;
                    client.EndConnect(connect);
                    client.ReceiveTimeout = 700; client.SendTimeout = 700;
                    using (NetworkStream stream = client.GetStream())
                    {
                        MemoryStream handshake = new MemoryStream();
                        WriteVarInt(handshake, 0); WriteVarInt(handshake, 769); WriteString(handshake, "127.0.0.1"); handshake.WriteByte((byte)(port >> 8)); handshake.WriteByte((byte)port); WriteVarInt(handshake, 1);
                        WritePacket(stream, handshake.ToArray()); WritePacket(stream, new byte[] { 0 });
                        ReadVarInt(stream); ReadVarInt(stream); string json = ReadString(stream);
                        Match online = Regex.Match(json, "\\\"online\\\"\\s*:\\s*(\\d+)");
                        Match max = Regex.Match(json, "\\\"max\\\"\\s*:\\s*(\\d+)");
                        if (!online.Success || !max.Success) return null;
                        return new int[] { Int32.Parse(online.Groups[1].Value), Int32.Parse(max.Groups[1].Value) };
                    }
                }
            }
            catch { return null; }
        }

        private static void WritePacket(Stream stream, byte[] data) { WriteVarInt(stream, data.Length); stream.Write(data, 0, data.Length); stream.Flush(); }
        private static void WriteString(Stream stream, string value) { byte[] bytes = Encoding.UTF8.GetBytes(value); WriteVarInt(stream, bytes.Length); stream.Write(bytes, 0, bytes.Length); }
        private static string ReadString(Stream stream) { int length = ReadVarInt(stream); byte[] data = new byte[length]; int offset = 0; while (offset < length) { int read = stream.Read(data, offset, length-offset); if (read <= 0) throw new EndOfStreamException(); offset += read; } return Encoding.UTF8.GetString(data); }
        private static void WriteVarInt(Stream stream, int value) { uint current = (uint)value; do { byte temp = (byte)(current & 0x7F); current >>= 7; if (current != 0) temp |= 0x80; stream.WriteByte(temp); } while (current != 0); }
        private static int ReadVarInt(Stream stream) { int result=0, shift=0; while (shift<35) { int raw=stream.ReadByte(); if(raw<0) throw new EndOfStreamException(); result |= (raw & 0x7F) << shift; if((raw & 0x80)==0) return result; shift+=7; } throw new InvalidDataException(); }

        private void SelectService(ServiceDefinition definition)
        {
            selected = definition;
            UpdateSelectedLabel();
            lastLogIdentity = "";
            RefreshAll();
        }

        private void SetLogMode(string mode)
        {
            logMode = mode;
            lastLogIdentity = "";
            UpdateSelectedLabel();
            RefreshLog();
        }

        private void UpdateSelectedLabel()
        {
            string title = logMode == "latest" ? "PAPER LOG" : logMode == "error" ? "ERROR LOG" : "KONZOLA";
            selectedLabel.Text = title + "  /  " + selected.Name.ToUpperInvariant();
        }

        private void RefreshLog()
        {
            string serviceRoot = IOPath.Combine(root, selected.RelativePath);
            string file = logMode == "latest" ? IOPath.Combine(serviceRoot, "logs", "latest.log") : logMode == "error" ? IOPath.Combine(serviceRoot, "console-error.log") : IOPath.Combine(serviceRoot, "console.log");
            try
            {
                if (!File.Exists(file)) { console.Text = "Zatiaľ nie je dostupný zvolený log."; UpdateConsoleAccess(); return; }
                FileInfo info = new FileInfo(file);
                string filter = consoleFilter == null ? "" : consoleFilter.Text.Trim();
                string identity = file + "|" + info.Length + "|" + info.LastWriteTimeUtc.Ticks + "|" + filter;
                if (identity == lastLogIdentity) return;
                lastLogIdentity = identity;
                long take = Math.Min(info.Length, 96L * 1024L);
                byte[] data = new byte[take];
                using (FileStream stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) { stream.Seek(-take, SeekOrigin.End); stream.Read(data, 0, data.Length); }
                string text = Encoding.UTF8.GetString(data);
                if (take < info.Length) { int newline = text.IndexOf('\n'); if (newline >= 0) text = text.Substring(newline + 1); }
                if (filter.Length > 0)
                {
                    string[] lines = text.Replace("\r\n", "\n").Split('\n');
                    StringBuilder filtered = new StringBuilder();
                    foreach (string line in lines) if (line.IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0) filtered.AppendLine(line);
                    text = filtered.Length == 0 ? "Žiadne riadky nezodpovedajú filtru „" + filter + "“." : filtered.ToString();
                }
                console.Text = text;
                console.ScrollToEnd();
            }
            catch (Exception exception) { console.Text = "Log sa nedá načítať: " + exception.Message; }
            UpdateConsoleAccess();
        }

        private void UpdateConsoleAccess()
        {
            RuntimeSnapshot snapshot;
            bool managed = snapshots.TryGetValue(selected.Id, out snapshot) && snapshot.Managed;
            command.IsEnabled = managed;
            sendButton.IsEnabled = managed;
            command.ToolTip = managed ? "Príkaz bez úvodného lomítka" : "Konzola bude zapisovateľná po štarte servera cez SkyBit Management.";
        }

        private void SendConsoleCommand()
        {
            string value = command.Text.Trim().TrimStart('/');
            if (value.Length == 0) return;
            ServiceDefinition target = selected;
            command.Clear();
            ThreadPool.QueueUserWorkItem(delegate
            {
                bool sent = ProcessTools.SendCommand(target.Id, value, 700);
                Dispatcher.BeginInvoke(new Action(delegate { if (!sent) MessageBox.Show(this, "Konzola servera nie je dostupná. Server musí byť spustený cez SkyBit Management.", "SkyBit Management", MessageBoxButton.OK, MessageBoxImage.Information); }));
            });
        }

        private void StartService(ServiceDefinition definition)
        {
            if (ProcessTools.ReadLivePid(Paths.Pid(root, definition.Id)) != 0) return;
            string executable = Assembly.GetExecutingAssembly().Location;
            ProcessStartInfo start = new ProcessStartInfo(executable, "--host " + definition.Id);
            start.UseShellExecute = false; start.CreateNoWindow = true; start.WindowStyle = ProcessWindowStyle.Hidden;
            try { Process.Start(start); }
            catch (Exception exception) { MessageBox.Show(this, "Server sa nepodarilo spustiť:\n" + exception.Message, "SkyBit Management", MessageBoxButton.OK, MessageBoxImage.Error); }
            Dispatcher.BeginInvoke(new Action(RefreshAll), DispatcherPriority.Background);
        }

        private void StopService(ServiceDefinition definition, bool silent)
        {
            RuntimeSnapshot snapshot;
            if (!snapshots.TryGetValue(definition.Id, out snapshot) || !snapshot.Running) return;
            if (!snapshot.Managed)
            {
                if (!silent) MessageBox.Show(this, "Tento server bol spustený mimo SkyBit Management. Kvôli ochrane sveta ho aplikácia násilne nevypne. Po najbližšom štarte cez túto aplikáciu bude STOP aj živá konzola dostupná.", "Bezpečné zastavenie", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }
            requestedStops.Add(definition.Id);
            ThreadPool.QueueUserWorkItem(delegate
            {
                bool sent = ProcessTools.SendCommand(definition.Id, definition.StopCommand, 800);
                if (!sent) Dispatcher.BeginInvoke(new Action(delegate { requestedStops.Remove(definition.Id); AddAlert("WARNING", definition.Name + " neprijal príkaz na zastavenie."); }));
            });
        }

        private void RestartService(ServiceDefinition definition)
        {
            RuntimeSnapshot snapshot;
            if (!snapshots.TryGetValue(definition.Id, out snapshot) || !snapshot.Managed) { StopService(definition, false); return; }
            requestedStops.Add(definition.Id);
            ThreadPool.QueueUserWorkItem(delegate
            {
                ProcessTools.SendCommand(definition.Id, definition.StopCommand, 800);
                DateTime until = DateTime.UtcNow.AddSeconds(35);
                while (DateTime.UtcNow < until && ProcessTools.ReadLivePid(Paths.Pid(root, definition.Id)) != 0) Thread.Sleep(500);
                if (ProcessTools.ReadLivePid(Paths.Pid(root, definition.Id)) == 0) Dispatcher.BeginInvoke(new Action(delegate { StartService(definition); }));
                else Dispatcher.BeginInvoke(new Action(delegate { MessageBox.Show(this, "Server sa do 35 sekúnd nezastavil. Reštart bol z bezpečnostných dôvodov prerušený.", "SkyBit Management", MessageBoxButton.OK, MessageBoxImage.Warning); }));
            });
        }

        private void StartAll()
        {
            foreach (ServiceDefinition definition in definitions) if (ProcessTools.ReadLivePid(Paths.Pid(root, definition.Id)) == 0) StartService(definition);
        }

        private void StopAll()
        {
            for (int index=definitions.Count-1; index>=0; index--) StopService(definitions[index], true);
        }

        private void AddAlert(string severity, string message)
        {
            string item = "[" + DateTime.Now.ToString("HH:mm:ss") + "] " + severity + " • " + message;
            alerts.Insert(0, item);
            if (alerts.Count > 25) alerts.RemoveRange(25, alerts.Count - 25);
            try
            {
                string directory = IOPath.Combine(root, "management");
                Directory.CreateDirectory(directory);
                using (FileStream stream = new FileStream(IOPath.Combine(directory, "events.log"), FileMode.Append, FileAccess.Write, FileShare.ReadWrite))
                using (StreamWriter writer = new StreamWriter(stream, new UTF8Encoding(false))) writer.WriteLine(DateTime.Now.ToString("s") + " " + severity + " " + message);
            }
            catch { }
            UpdateAlerts();
        }

        private void UpdateAlerts()
        {
            if (alertCount == null || alertLatest == null) return;
            alertCount.Text = alerts.Count.ToString();
            alertLatest.Text = alerts.Count == 0 ? "Žiadne nové upozornenia." : alerts[0];
            string color = alerts.Count == 0 ? "#67F5A2" : alerts[0].Contains("CRITICAL") ? "#FF6878" : alerts[0].Contains("WARNING") ? "#FFB85C" : "#20E3F0";
            alertCount.Foreground = Brush(color);
            alertLatest.Foreground = Brush(alerts.Count == 0 ? "#8391A3" : color);
        }

        private void BackupConfiguration()
        {
            ServiceDefinition target = selected;
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    string serviceRoot = IOPath.GetFullPath(IOPath.Combine(root, target.RelativePath));
                    string outputDirectory = IOPath.Combine(root, "backups", "manager");
                    Directory.CreateDirectory(outputDirectory);
                    string output = IOPath.Combine(outputDirectory, DateTime.Now.ToString("yyyyMMdd-HHmmss") + "-" + target.Id + "-config.zip");
                    int files = 0;
                    using (FileStream stream = new FileStream(output, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None))
                    using (ZipArchive archive = new ZipArchive(stream, ZipArchiveMode.Create))
                    {
                        foreach (string file in Directory.GetFiles(serviceRoot, "*", SearchOption.TopDirectoryOnly)) if (IsConfigFile(file)) { AddConfigEntry(archive, serviceRoot, file); files++; }
                        string plugins = IOPath.Combine(serviceRoot, "plugins");
                        if (Directory.Exists(plugins)) files += AddConfigTree(archive, serviceRoot, plugins);
                    }
                    Dispatcher.BeginInvoke(new Action(delegate
                    {
                        AddAlert("INFO", "Záloha " + target.Name + " je hotová (" + files + " súborov).");
                        MessageBox.Show(this, "Konfigurácia servera " + target.Name + " bola bezpečne uložená do:\n" + output, "Záloha hotová", MessageBoxButton.OK, MessageBoxImage.Information);
                    }));
                }
                catch (Exception exception)
                {
                    Dispatcher.BeginInvoke(new Action(delegate { AddAlert("WARNING", "Záloha " + target.Name + " zlyhala."); MessageBox.Show(this, "Záloha sa nepodarila:\n" + exception.Message, "SkyBit Management", MessageBoxButton.OK, MessageBoxImage.Error); }));
                }
            });
        }

        private static int AddConfigTree(ZipArchive archive, string serviceRoot, string directory)
        {
            int count = 0;
            foreach (string file in Directory.GetFiles(directory)) if (IsConfigFile(file)) { AddConfigEntry(archive, serviceRoot, file); count++; }
            foreach (string child in Directory.GetDirectories(directory))
            {
                string name = IOPath.GetFileName(child).ToLowerInvariant();
                FileAttributes attributes;
                try { attributes = File.GetAttributes(child); } catch { continue; }
                if ((attributes & FileAttributes.ReparsePoint) != 0 || name == "logs" || name == "cache" || name == ".cache" || name == "libraries") continue;
                count += AddConfigTree(archive, serviceRoot, child);
            }
            return count;
        }

        private static void AddConfigEntry(ZipArchive archive, string serviceRoot, string file)
        {
            string entry = file.Substring(serviceRoot.Length).TrimStart(IOPath.DirectorySeparatorChar, IOPath.AltDirectorySeparatorChar).Replace('\\', '/');
            archive.CreateEntryFromFile(file, entry, CompressionLevel.Optimal);
        }

        private static bool IsConfigFile(string file)
        {
            string name = IOPath.GetFileName(file).ToLowerInvariant();
            string extension = IOPath.GetExtension(file).ToLowerInvariant();
            return extension == ".yml" || extension == ".yaml" || extension == ".json" || extension == ".toml" || extension == ".conf" || extension == ".properties" || extension == ".txt" || extension == ".xml" || extension == ".lang" || extension == ".secret" || name == "forwarding.secret";
        }

        private void ExportDiagnostics()
        {
            Dictionary<string, RuntimeSnapshot> current = new Dictionary<string, RuntimeSnapshot>(snapshots);
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    string directory = IOPath.Combine(root, "management", "reports");
                    Directory.CreateDirectory(directory);
                    string output = IOPath.Combine(directory, "diagnostics-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt");
                    StringBuilder report = new StringBuilder();
                    report.AppendLine("SkyBit Management diagnostic report");
                    report.AppendLine("Generated: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss zzz"));
                    report.AppendLine("App: " + Assembly.GetExecutingAssembly().GetName().Version);
                    report.AppendLine("OS: " + Environment.OSVersion);
                    report.AppendLine("CPU cores: " + Environment.ProcessorCount);
                    report.AppendLine("Java runtime: " + (File.Exists(IOPath.Combine(root, "runtime", "java-25", "bin", "java.exe")) ? "OK" : "MISSING"));
                    report.AppendLine();
                    foreach (ServiceDefinition definition in definitions)
                    {
                        RuntimeSnapshot snapshot;
                        current.TryGetValue(definition.Id, out snapshot);
                        report.AppendLine("=== " + definition.Name + " ===");
                        report.AppendLine("Port: " + definition.Port);
                        report.AppendLine(snapshot == null ? "State: unknown" : String.Format("State: {0}; managed={1}; pid={2}; players={3}/{4}; cpu={5:0.0}%; ram={6}; uptime={7}", snapshot.Running ? "online" : "offline", snapshot.Managed, snapshot.ProcessId, snapshot.Players, snapshot.MaxPlayers, snapshot.CpuPercent, FormatBytes(snapshot.MemoryBytes), FormatUptime(snapshot.Uptime)));
                        string error = IOPath.Combine(root, definition.RelativePath, "console-error.log");
                        string tail = ReadTail(error, 20);
                        if (tail.Length > 0) report.AppendLine("Recent stderr:\n" + RedactAddresses(tail));
                        report.AppendLine();
                    }
                    File.WriteAllText(output, report.ToString(), new UTF8Encoding(true));
                    Dispatcher.BeginInvoke(new Action(delegate { AddAlert("INFO", "Diagnostický report bol vytvorený."); OpenFolder(directory); }));
                }
                catch (Exception exception)
                {
                    Dispatcher.BeginInvoke(new Action(delegate { AddAlert("WARNING", "Diagnostický report zlyhal."); MessageBox.Show(this, "Report sa nepodarilo vytvoriť:\n" + exception.Message, "SkyBit Management", MessageBoxButton.OK, MessageBoxImage.Error); }));
                }
            });
        }

        private static string ReadTail(string file, int lineCount)
        {
            if (!File.Exists(file)) return "";
            FileInfo info = new FileInfo(file);
            int take = (int)Math.Min(info.Length, 64L * 1024L);
            if (take == 0) return "";
            byte[] data = new byte[take];
            using (FileStream stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) { stream.Seek(-take, SeekOrigin.End); stream.Read(data, 0, take); }
            string[] lines = Encoding.UTF8.GetString(data).Replace("\r\n", "\n").Split('\n');
            int start = Math.Max(0, lines.Length - lineCount - 1);
            return String.Join(Environment.NewLine, lines, start, lines.Length - start).Trim();
        }

        private static string RedactAddresses(string text)
        {
            return Regex.Replace(text, @"\b(?:\d{1,3}\.){3}\d{1,3}\b", "[IP]");
        }

        private static void OpenFolder(string path)
        {
            try { Process.Start("explorer.exe", "\"" + path + "\""); } catch { }
        }

        private static string FormatBytes(long bytes) { if (bytes <= 0) return "—"; return (bytes / 1024d / 1024d / 1024d).ToString("0.00") + " GB"; }
        private static string FormatUptime(TimeSpan value) { if (value.TotalDays >= 1) return ((int)value.TotalDays) + "d " + value.Hours + "h"; if (value.TotalHours >= 1) return ((int)value.TotalHours) + "h " + value.Minutes + "m"; return Math.Max(0, value.Minutes) + "m"; }
        private static SolidColorBrush Brush(string hex) { return (SolidColorBrush)new BrushConverter().ConvertFromString(hex); }
        private static TextBlock Text(string value, double size, FontWeight weight, string color) { return new TextBlock { Text=value, FontSize=size, FontWeight=weight, Foreground=Brush(color) }; }

        private static Button ActionButton(string text, string background, string foreground)
        {
            Button button = new Button { Content=text, Background=Brush(background), Foreground=Brush(foreground), BorderThickness=new Thickness(0), Padding=new Thickness(14, 10, 14, 10), FontSize=9, FontWeight=FontWeights.Bold, Cursor=Cursors.Hand };
            return button;
        }

        private static Button OutlineButton(string text)
        {
            return new Button { Content=text, Background=Brush("#0A111A"), Foreground=Brush("#B8C4D1"), BorderBrush=Brush("#223140"), BorderThickness=new Thickness(1), Padding=new Thickness(13, 10, 13, 10), FontSize=9, FontWeight=FontWeights.Bold, Cursor=Cursors.Hand };
        }

        private static Button SmallButton(string text, string color)
        {
            return new Button { Content=text, Background=Brush("#0B131C"), Foreground=Brush(color), BorderBrush=Brush("#263647"), BorderThickness=new Thickness(1), Padding=new Thickness(12, 7, 12, 7), FontSize=8, FontWeight=FontWeights.Bold, Cursor=Cursors.Hand };
        }
    }

    internal static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            string root = Paths.Root();
            if (args.Length >= 2 && args[0] == "--host")
            {
                foreach (ServiceDefinition definition in ServiceDefinition.All()) if (definition.Id.Equals(args[1], StringComparison.OrdinalIgnoreCase)) return ServiceHost.Run(root, definition);
                return 2;
            }
            if (args.Length >= 1 && args[0] == "--self-test") return SelfTest(root);
            if (args.Length >= 2 && args[0] == "--preview") return Preview(root, args[1]);
            Application application = new Application();
            application.ShutdownMode = ShutdownMode.OnMainWindowClose;
            application.Run(new MainWindow(root));
            return 0;
        }

        private static int SelfTest(string root)
        {
            StringBuilder report = new StringBuilder();
            bool ok = true;
            string java = IOPath.Combine(root, "runtime", "java-25", "bin", "java.exe");
            report.AppendLine("SkyBit Management self-test");
            report.AppendLine("Root: " + root);
            report.AppendLine("Java: " + (File.Exists(java) ? "OK" : "MISSING"));
            if (!File.Exists(java)) ok = false;
            try
            {
                using (MemoryStream zipBuffer = new MemoryStream())
                using (ZipArchive archive = new ZipArchive(zipBuffer, ZipArchiveMode.Create, true)) archive.CreateEntry("self-test.txt");
                report.AppendLine("Config ZIP: OK");
            }
            catch (Exception exception) { report.AppendLine("Config ZIP: FAILED (" + exception.Message + ")"); ok = false; }
            foreach (ServiceDefinition definition in ServiceDefinition.All())
            {
                bool directory = Directory.Exists(IOPath.Combine(root, definition.RelativePath));
                bool jar = File.Exists(IOPath.Combine(root, definition.RelativePath, definition.Jar));
                int pid = ProcessTools.ReadLivePid(Paths.Pid(root, definition.Id));
                report.AppendLine(String.Format("{0}: directory={1}, jar={2}, port={3}, pid={4}", definition.Id, directory ? "OK" : "MISSING", jar ? "OK" : "MISSING", definition.Port, pid == 0 ? "offline" : pid.ToString()));
                if (!directory || !jar) ok = false;
            }
            string output = IOPath.Combine(IOPath.GetDirectoryName(Assembly.GetExecutingAssembly().Location), "self-test.txt");
            File.WriteAllText(output, report.ToString(), Encoding.UTF8);
            return ok ? 0 : 1;
        }

        private static int Preview(string root, string output)
        {
            Application application = new Application();
            MainWindow window = new MainWindow(root);
            window.Width = 1440; window.Height = 900; window.Left = -10000; window.Top = -10000; window.ShowInTaskbar = false;
            window.Show();
            DispatcherTimer previewTimer = new DispatcherTimer();
            previewTimer.Interval = TimeSpan.FromSeconds(3);
            previewTimer.Tick += delegate
            {
                previewTimer.Stop();
                window.UpdateLayout();
                RenderTargetBitmap bitmap = new RenderTargetBitmap(1440, 900, 96, 96, PixelFormats.Pbgra32);
                bitmap.Render(window);
                PngBitmapEncoder encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
                using (FileStream file = new FileStream(output, FileMode.Create)) encoder.Save(file);
                window.Close(); application.Shutdown();
            };
            previewTimer.Start();
            application.Run();
            return 0;
        }
    }
}
