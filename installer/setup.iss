#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppName=GameTimer
AppVersion={#AppVersion}
AppPublisher=GameTimer
DefaultDirName={autopf}\GameTimer
DefaultGroupName=GameTimer
OutputDir=Output
OutputBaseFilename=GameTimerSetup
Compression=lzma2
SolidCompression=yes
SetupIconFile=..\src\GameTimer\Resources\app.ico
UninstallDisplayIcon={app}\SystemMonitor.exe
PrivilegesRequired=lowest
WizardStyle=modern
; The app refuses the Restart Manager on purpose (it hides to the tray and may deny termination), so
; Setup never tries to close it that way. [Code] below asks the app to close itself instead.
CloseApplications=no
RestartApplications=no

[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{group}\GameTimer"; Filename: "{app}\SystemMonitor.exe"
Name: "{autodesktop}\GameTimer"; Filename: "{app}\SystemMonitor.exe"; Tasks: desktopicon
Name: "{userstartup}\GameTimer"; Filename: "{app}\SystemMonitor.exe"; Tasks: autostart

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"
Name: "autostart"; Description: "Start with Windows"; GroupDescription: "Additional options:"

[Run]
; Started again automatically if it was running before the update (also in silent mode),
; so a running timer continues; otherwise the usual "Launch GameTimer" checkbox.
Filename: "{app}\SystemMonitor.exe"; Flags: nowait; Check: WasAppRunning
Filename: "{app}\SystemMonitor.exe"; Description: "Launch GameTimer"; Flags: nowait postinstall skipifsilent; Check: not WasAppRunning

[Code]
const
  // Must match App.xaml.cs
  AppMutexName  = 'Local\SystemMonitor_7F3A2C1E_Instance';
  ExitEventName = 'Local\SystemMonitor_7F3A2C1E_Exit';
  EVENT_MODIFY_STATE = $0002;
  WaitSeconds = 60;

var
  AppWasRunning: Boolean;

function OpenEvent(dwDesiredAccess: DWORD; bInheritHandle: BOOL; lpName: String): THandle;
  external 'OpenEventW@kernel32.dll stdcall';
function SetEvent(hEvent: THandle): BOOL;
  external 'SetEvent@kernel32.dll stdcall';
function CloseHandle(hObject: THandle): BOOL;
  external 'CloseHandle@kernel32.dll stdcall';

function AppIsRunning: Boolean;
begin
  Result := CheckForMutexes(AppMutexName);
end;

function WasAppRunning: Boolean;
begin
  Result := AppWasRunning;
end;

// Signals the running app to close for the update. The app asks the parent (Windows Hello) when a
// timer or the schedule is active, saves a running timer and exits. Returns True once it is gone.
// Versions before 1.6.1 don't listen for the signal: then only the tray exit helps.
function AskAppToExit: Boolean;
var
  Handle: THandle;
  I: Integer;
begin
  Result := not AppIsRunning;
  if Result then Exit;
  Handle := OpenEvent(EVENT_MODIFY_STATE, False, ExitEventName);
  if Handle = 0 then Exit; // an older version without the signal: no point waiting
  SetEvent(Handle);
  CloseHandle(Handle);
  for I := 1 to WaitSeconds * 2 do
  begin
    Sleep(500);
    if not AppIsRunning then
    begin
      Sleep(500); // let the process finish releasing its files
      Result := True;
      Exit;
    end;
  end;
end;

// Keeps asking until the app has closed or the user gives up. Returns True when it is safe to go on.
function CloseRunningApp(const Action: String): Boolean;
begin
  Result := True;
  while AppIsRunning do
  begin
    if AskAppToExit then Exit;
    if SuppressibleMsgBox(
         'GameTimer is still running, so it can''t be ' + Action + ' yet.' + #13#10#13#10 +
         'Confirm closing it in the Windows Hello prompt (it may be behind this window), ' +
         'or exit it from its tray icon. Then click Retry.',
         mbError, MB_RETRYCANCEL, IDCANCEL) <> IDRETRY then
    begin
      Result := False;
      Exit;
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  AppWasRunning := AppIsRunning;
  if not CloseRunningApp('updated') then
    Result := 'GameTimer is still running. Close it and run Setup again.';
end;

function InitializeUninstall: Boolean;
begin
  Result := CloseRunningApp('uninstalled');
end;
