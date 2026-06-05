using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;
using ZXing;
using ZXing.Windows.Compatibility;

namespace AereNASClient
{
    internal static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new TrayApp());
        }
    }

    // ── Win32 Drive Mapping ───────────────────────────────────────────────────

    internal static class DriveMapper
    {
        [DllImport("mpr.dll", CharSet = CharSet.Unicode)]
        private static extern int WNetAddConnection2(ref NETRESOURCE netResource,
            string password, string username, int flags);

        [DllImport("mpr.dll", CharSet = CharSet.Unicode)]
        private static extern int WNetCancelConnection2(string name, int flags, bool force);

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct NETRESOURCE
        {
            public int dwScope, dwType, dwDisplayType, dwUsage;
            public string lpLocalName, lpRemoteName, lpComment, lpProvider;
        }

        private const int RESOURCETYPE_DISK = 1;
        private const int CONNECT_UPDATE_PROFILE = 0x00000001;

        public static bool MapDrive(string driveLetter, string url, string username, string password)
        {
            // Ensure WebClient service is configured for HTTP basic auth
            FixWebClientRegistry();
            RestartWebClientService();

            // Disconnect existing mapping if any
            WNetCancelConnection2(driveLetter, 0, true);
            Thread.Sleep(500);

            var netRes = new NETRESOURCE
            {
                dwType      = RESOURCETYPE_DISK,
                lpLocalName = driveLetter,
                lpRemoteName = url
            };

            int result = WNetAddConnection2(ref netRes, password, username, CONNECT_UPDATE_PROFILE);
            return result == 0;
        }

        public static void UnmapDrive(string driveLetter)
        {
            WNetCancelConnection2(driveLetter, 0, true);
        }

        private static void FixWebClientRegistry()
        {
            try
            {
                using var key = Registry.LocalMachine.OpenSubKey(
                    @"SYSTEM\CurrentControlSet\Services\WebClient\Parameters", true);
                key?.SetValue("BasicAuthLevel", 2, RegistryValueKind.DWord);
            }
            catch { /* needs admin — handled at startup */ }
        }

        private static void RestartWebClientService()
        {
            try
            {
                using var sc = new System.ServiceProcess.ServiceController("WebClient");
                if (sc.Status != System.ServiceProcess.ServiceControllerStatus.Running)
                    sc.Start();
                else
                {
                    sc.Stop();
                    sc.WaitForStatus(System.ServiceProcess.ServiceControllerStatus.Stopped,
                        TimeSpan.FromSeconds(5));
                    sc.Start();
                }
                sc.WaitForStatus(System.ServiceProcess.ServiceControllerStatus.Running,
                    TimeSpan.FromSeconds(10));
            }
            catch { }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    internal class Settings
    {
        private static readonly string ConfigPath =
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "AereNAS", "config.ini");

        public string Ip          { get; set; } = "";
        public int    Port        { get; set; } = 8080;
        public string Username    { get; set; } = "";
        public string Password    { get; set; } = "";
        public string DriveLetter { get; set; } = "Z:";
        public string SyncSource  { get; set; } = "";
        public string SyncDest    { get; set; } = "Z:\\";
        public bool   AutoSync    { get; set; } = true;
        public int    SyncInterval{ get; set; } = 15; // minutes
        public bool   AutoConnect { get; set; } = true;

        public string WebDavUrl => $"http://{Ip}:{Port}";

        public static Settings Load()
        {
            var s = new Settings();
            if (!File.Exists(ConfigPath)) return s;
            foreach (var line in File.ReadAllLines(ConfigPath))
            {
                var parts = line.Split('=', 2);
                if (parts.Length != 2) continue;
                switch (parts[0].Trim())
                {
                    case "ip":           s.Ip           = parts[1].Trim(); break;
                    case "port":         s.Port         = int.TryParse(parts[1].Trim(), out var p) ? p : 8080; break;
                    case "username":     s.Username     = parts[1].Trim(); break;
                    case "password":     s.Password     = parts[1].Trim(); break;
                    case "drive_letter": s.DriveLetter  = parts[1].Trim(); break;
                    case "sync_source":  s.SyncSource   = parts[1].Trim(); break;
                    case "sync_dest":    s.SyncDest     = parts[1].Trim(); break;
                    case "auto_sync":    s.AutoSync     = parts[1].Trim() == "true"; break;
                    case "sync_interval":s.SyncInterval = int.TryParse(parts[1].Trim(), out var si) ? si : 15; break;
                    case "auto_connect": s.AutoConnect  = parts[1].Trim() == "true"; break;
                }
            }
            return s;
        }

        public void Save()
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
            File.WriteAllText(ConfigPath, $@"ip={Ip}
port={Port}
username={Username}
password={Password}
drive_letter={DriveLetter}
sync_source={SyncSource}
sync_dest={SyncDest}
auto_sync={AutoSync.ToString().ToLower()}
sync_interval={SyncInterval}
auto_connect={AutoConnect.ToString().ToLower()}
");
        }
    }

    // ── Sync Engine ───────────────────────────────────────────────────────────

    internal class SyncEngine
    {
        private System.Threading.Timer? _timer;
        private readonly Action<string> _log;

        public SyncEngine(Action<string> log) { _log = log; }

        public void Start(Settings settings)
        {
            Stop();
            if (!settings.AutoSync || string.IsNullOrEmpty(settings.SyncSource)) return;
            var interval = TimeSpan.FromMinutes(settings.SyncInterval);
            _timer = new System.Threading.Timer(_ => RunSync(settings), null, TimeSpan.Zero, interval);
        }

        public void Stop() { _timer?.Dispose(); _timer = null; }

        private void RunSync(Settings settings)
        {
            if (!Directory.Exists(settings.SyncSource))
            {
                _log($"[Sync] Source not found: {settings.SyncSource}");
                return;
            }

            // Check drive is accessible
            if (!Directory.Exists(settings.SyncDest))
            {
                _log("[Sync] Drive not accessible, skipping");
                return;
            }

            _log("[Sync] Starting move sync...");
            int moved = 0, failed = 0;

            foreach (var file in Directory.GetFiles(settings.SyncSource, "*", SearchOption.AllDirectories))
            {
                try
                {
                    var relative = Path.GetRelativePath(settings.SyncSource, file);
                    var dest     = Path.Combine(settings.SyncDest, relative);
                    var destDir  = Path.GetDirectoryName(dest)!;

                    Directory.CreateDirectory(destDir);

                    // Copy then verify, then delete source (safer than Move across network)
                    File.Copy(file, dest, overwrite: true);
                    var srcInfo  = new FileInfo(file);
                    var destInfo = new FileInfo(dest);

                    if (destInfo.Length == srcInfo.Length)
                    {
                        File.Delete(file);
                        moved++;
                        _log($"[Sync] Moved: {relative}");
                    }
                    else
                    {
                        failed++;
                        _log($"[Sync] Verify failed, kept: {relative}");
                    }
                }
                catch (Exception ex)
                {
                    failed++;
                    _log($"[Sync] Error on {Path.GetFileName(file)}: {ex.Message}");
                }
            }

            _log($"[Sync] Done. Moved: {moved}, Failed: {failed}");
        }
    }

    // ── Tray Application ──────────────────────────────────────────────────────

    internal class TrayApp : ApplicationContext
    {
        private NotifyIcon   _tray     = null!;
        private Settings     _settings = null!;
        private SyncEngine   _sync     = null!;
        private bool         _connected = false;
        private System.Threading.Timer? _reconnectTimer;

        public TrayApp()
        {
            _settings = Settings.Load();
            _sync     = new SyncEngine(Log);

            BuildTray();

            if (_settings.AutoConnect && !string.IsNullOrEmpty(_settings.Ip))
                Task.Run(Connect);

            // Reconnect check every 60s
            _reconnectTimer = new System.Threading.Timer(_ =>
            {
                if (_settings.AutoConnect && !string.IsNullOrEmpty(_settings.Ip) && !_connected)
                    Task.Run(Connect);
            }, null, TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(1));
        }

        private void BuildTray()
        {
            var menu = new ContextMenuStrip();
            menu.Items.Add("AereNAS", null).Enabled = false;
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Connect",      null, (_, _) => Task.Run(Connect));
            menu.Items.Add("Disconnect",   null, (_, _) => Disconnect());
            menu.Items.Add("Sync Now",     null, (_, _) => Task.Run(() => _sync.Start(_settings)));
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Scan QR Code", null, (_, _) => ScanQr());
            menu.Items.Add("Settings",     null, (_, _) => OpenSettings());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Exit",         null, (_, _) => ExitApp());

            _tray = new NotifyIcon
            {
                Icon             = SystemIcons.Application,
                Text             = "AereNAS — Disconnected",
                Visible          = true,
                ContextMenuStrip = menu
            };
        }

        private async Task Connect()
        {
            SetTrayText("AereNAS — Connecting…");
            Log($"Connecting to {_settings.WebDavUrl}...");

            bool ok = DriveMapper.MapDrive(
                _settings.DriveLetter,
                _settings.WebDavUrl,
                _settings.Username,
                _settings.Password);

            _connected = ok;
            if (ok)
            {
                SetTrayText($"AereNAS — {_settings.DriveLetter} Connected");
                Log($"Drive {_settings.DriveLetter} mapped successfully");
                _tray.ShowBalloonTip(3000, "AereNAS", $"Drive {_settings.DriveLetter} connected", ToolTipIcon.Info);
                if (_settings.AutoSync) _sync.Start(_settings);
            }
            else
            {
                SetTrayText("AereNAS — Connection Failed");
                Log("Drive mapping failed — check phone IP and server status");
                _tray.ShowBalloonTip(3000, "AereNAS", "Connection failed. Check phone.", ToolTipIcon.Warning);
            }
        }

        private void Disconnect()
        {
            _sync.Stop();
            DriveMapper.UnmapDrive(_settings.DriveLetter);
            _connected = false;
            SetTrayText("AereNAS — Disconnected");
            Log("Disconnected");
        }

        private void ScanQr()
        {
            // Open webcam capture dialog to scan QR
            using var form = new QrScanForm();
            if (form.ShowDialog() == DialogResult.OK && form.ScannedUri != null)
            {
                ParseAndApplyUri(form.ScannedUri);
                _settings.Save();
                Task.Run(Connect);
            }
        }

        private void ParseAndApplyUri(string uri)
        {
            // Format: aerenas://user:password@ip:port
            var match = Regex.Match(uri, @"aerenas://(.+):(.+)@(.+):(\d+)");
            if (match.Success)
            {
                _settings.Username = match.Groups[1].Value;
                _settings.Password = match.Groups[2].Value;
                _settings.Ip       = match.Groups[3].Value;
                _settings.Port     = int.Parse(match.Groups[4].Value);
                Log($"QR parsed: {_settings.Ip}:{_settings.Port} user={_settings.Username}");
            }
        }

        private void OpenSettings()
        {
            using var form = new SettingsForm(_settings);
            if (form.ShowDialog() == DialogResult.OK)
            {
                _settings = form.UpdatedSettings;
                _settings.Save();
                Disconnect();
                Task.Run(Connect);
            }
        }

        private void ExitApp()
        {
            Disconnect();
            _reconnectTimer?.Dispose();
            _tray.Visible = false;
            Application.Exit();
        }

        private void SetTrayText(string text) =>
            _tray.Invoke(() => _tray.Text = text[..Math.Min(text.Length, 63)]);

        private void Log(string msg) =>
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] {msg}");
    }

    // ── QR Scan Form ──────────────────────────────────────────────────────────

    internal class QrScanForm : Form
    {
        public string? ScannedUri { get; private set; }
        private readonly TextBox _manualInput;

        public QrScanForm()
        {
            Text            = "AereNAS — Connect via QR";
            Size            = new Size(420, 220);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox     = false;
            StartPosition   = FormStartPosition.CenterScreen;
            BackColor       = Color.FromArgb(20, 20, 20);
            ForeColor       = Color.White;

            var label = new Label
            {
                Text     = "Paste the aerenas:// URI from QR code, or type manually:",
                Location = new Point(20, 20),
                Size     = new Size(360, 40),
                ForeColor = Color.FromArgb(136, 136, 136)
            };

            _manualInput = new TextBox
            {
                Location  = new Point(20, 70),
                Size      = new Size(360, 30),
                BackColor = Color.FromArgb(30, 30, 30),
                ForeColor = Color.White,
                PlaceholderText = "aerenas://user:password@192.168.x.x:8080",
                Font      = new Font("Consolas", 10)
            };

            var btnOk = new Button
            {
                Text     = "Connect",
                Location = new Point(20, 120),
                Size     = new Size(160, 36),
                BackColor = Color.FromArgb(79, 195, 247),
                ForeColor = Color.Black,
                FlatStyle = FlatStyle.Flat
            };
            btnOk.Click += (_, _) =>
            {
                ScannedUri = _manualInput.Text.Trim();
                DialogResult = DialogResult.OK;
            };

            var btnCancel = new Button
            {
                Text     = "Cancel",
                Location = new Point(200, 120),
                Size     = new Size(160, 36),
                BackColor = Color.FromArgb(40, 40, 40),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat
            };
            btnCancel.Click += (_, _) => DialogResult = DialogResult.Cancel;

            Controls.AddRange(new Control[] { label, _manualInput, btnOk, btnCancel });
        }
    }

    // ── Settings Form ─────────────────────────────────────────────────────────

    internal class SettingsForm : Form
    {
        public Settings UpdatedSettings { get; private set; }
        private readonly TextBox _tbIp, _tbPort, _tbUser, _tbPass, _tbDrive, _tbSyncSrc, _tbSyncDest, _tbInterval;
        private readonly CheckBox _cbAutoSync, _cbAutoConnect;

        public SettingsForm(Settings current)
        {
            UpdatedSettings = current;
            Text            = "AereNAS Settings";
            Size            = new Size(460, 480);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox     = false;
            StartPosition   = FormStartPosition.CenterScreen;
            BackColor       = Color.FromArgb(20, 20, 20);
            ForeColor       = Color.White;

            int y = 20;
            Label Lbl(string t) => new Label { Text = t, Location = new Point(20, y), Size = new Size(120, 24), ForeColor = Color.FromArgb(136,136,136) };
            TextBox Tb(string val) { var t = new TextBox { Location = new Point(150, y), Size = new Size(280, 24), Text = val, BackColor = Color.FromArgb(30,30,30), ForeColor = Color.White }; y += 32; return t; }

            Controls.Add(Lbl("Phone IP")); _tbIp       = Tb(current.Ip);       Controls.Add(_tbIp);
            Controls.Add(Lbl("Port"));    _tbPort      = Tb(current.Port.ToString()); Controls.Add(_tbPort);
            Controls.Add(Lbl("Username"));_tbUser      = Tb(current.Username); Controls.Add(_tbUser);
            Controls.Add(Lbl("Password"));_tbPass      = Tb(current.Password); Controls.Add(_tbPass);
            Controls.Add(Lbl("Drive Letter")); _tbDrive = Tb(current.DriveLetter); Controls.Add(_tbDrive);
            Controls.Add(Lbl("Sync Source")); _tbSyncSrc = Tb(current.SyncSource); Controls.Add(_tbSyncSrc);
            Controls.Add(Lbl("Sync Dest")); _tbSyncDest = Tb(current.SyncDest); Controls.Add(_tbSyncDest);
            Controls.Add(Lbl("Interval (min)")); _tbInterval = Tb(current.SyncInterval.ToString()); Controls.Add(_tbInterval);

            _cbAutoSync = new CheckBox { Text = "Auto-sync", Location = new Point(20, y), Checked = current.AutoSync, ForeColor = Color.White }; y += 30;
            _cbAutoConnect = new CheckBox { Text = "Auto-connect on start", Location = new Point(20, y), Checked = current.AutoConnect, ForeColor = Color.White }; y += 40;
            Controls.Add(_cbAutoSync); Controls.Add(_cbAutoConnect);

            var btnSave = new Button { Text = "Save", Location = new Point(20, y), Size = new Size(200, 36), BackColor = Color.FromArgb(79,195,247), ForeColor = Color.Black, FlatStyle = FlatStyle.Flat };
            btnSave.Click += (_, _) =>
            {
                UpdatedSettings = new Settings
                {
                    Ip           = _tbIp.Text.Trim(),
                    Port         = int.TryParse(_tbPort.Text, out var p) ? p : 8080,
                    Username     = _tbUser.Text.Trim(),
                    Password     = _tbPass.Text.Trim(),
                    DriveLetter  = _tbDrive.Text.Trim(),
                    SyncSource   = _tbSyncSrc.Text.Trim(),
                    SyncDest     = _tbSyncDest.Text.Trim(),
                    SyncInterval = int.TryParse(_tbInterval.Text, out var si) ? si : 15,
                    AutoSync     = _cbAutoSync.Checked,
                    AutoConnect  = _cbAutoConnect.Checked
                };
                DialogResult = DialogResult.OK;
            };
            Controls.Add(btnSave);
        }
    }
}
