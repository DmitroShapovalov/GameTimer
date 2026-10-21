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
; Started again automatically if it was running before the update (also in silent mode), as the
; user who started Setup (never elevated), so a running timer continues. Otherwise the usual checkbox.
Filename: "{app}\SystemMonitor.exe"; Flags: nowait runasoriginaluser; Check: WasAppRunning
Filename: "{app}\SystemMonitor.exe"; Description: "Launch GameTimer"; Flags: nowait postinstall skipifsilent; Check: not WasAppRunning

[UninstallDelete]
; A saved running timer must not survive an uninstall: a reinstall days later would treat it as
; expired and lock the PC at once. Settings and history are kept for a reinstall.
Type: files; Name: "{app}\timer-state.json*"

[Code]
const
  // Must match src/GameTimer/Services/SetupSignals.cs
  AppMutexName     = 'Local\SystemMonitor_7F3A2C1E_Instance';
  UpdateEventName  = 'Local\SystemMonitor_7F3A2C1E_Exit';
  RemoveEventName  = 'Local\SystemMonitor_7F3A2C1E_Uninstall';
  WaitingMutexName = 'Local\SystemMonitor_7F3A2C1E_SetupWaiting';
  EVENT_MODIFY_STATE = $0002;
  WaitSeconds = 60;

var
  AppWasRunning: Boolean;
  OldVersionRunning: Boolean;   // a running version without the close signal (v1.6.0 and older)
  Finished: Boolean;            // install / uninstall completed
  WaitingMutex: THandle;
  ProgressPage: TOutputProgressWizardPage;

function OpenEvent(dwDesiredAccess: DWORD; bInheritHandle: BOOL; lpName: String): THandle;
  external 'OpenEventW@kernel32.dll stdcall';
function SetEvent(hEvent: THandle): BOOL;
  external 'SetEvent@kernel32.dll stdcall';
function CreateMutex(lpMutexAttributes: Cardinal; bInitialOwner: BOOL; lpName: String): THandle;
  external 'CreateMutexW@kernel32.dll stdcall';
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

function AppExe: String;
begin
  Result := ExpandConstant('{app}\SystemMonitor.exe');
end;

// Tells the app that an installer is really waiting; the app checks this after the parent confirms.
procedure HoldWaitingMutex;
begin
  if WaitingMutex = 0 then
    WaitingMutex := CreateMutex(0, False, WaitingMutexName);
end;

procedure ReleaseWaitingMutex;
begin
  if WaitingMutex <> 0 then
  begin
    CloseHandle(WaitingMutex);
    WaitingMutex := 0;
  end;
end;

// Signals the running app to close. The app asks the parent (Windows Hello) when a timer or the
// schedule is active, then exits. Returns True once it is gone.
function AskAppToExit(const EventName: String): Boolean;
var
  Handle: THandle;
  I: Integer;
begin
  Result := not AppIsRunning;
  if Result then Exit;
  Handle := OpenEvent(EVENT_MODIFY_STATE, False, EventName);
  OldVersionRunning := Handle = 0;
  if OldVersionRunning then Exit; // nothing to signal: no point waiting
  HoldWaitingMutex;
  SetEvent(Handle);
  CloseHandle(Handle);

  if ProgressPage <> nil then
  begin
    ProgressPage.SetText('Waiting for GameTimer to close...',
      'If asked, confirm with Windows Hello (the prompt may be behind this window).');
    ProgressPage.Show;
  end;
  try
    for I := 1 to WaitSeconds * 2 do
    begin
      if ProgressPage <> nil then ProgressPage.SetProgress(I, WaitSeconds * 2);
      Sleep(500);
      if not AppIsRunning then
      begin
        // The mutex disappears only at process teardown; give Windows a moment to release the files
        Sleep(1000);
        Result := True;
        Exit;
      end;
    end;
  finally
    if ProgressPage <> nil then ProgressPage.Hide;
  end;
end;

// Keeps asking until the app has closed or the user gives up. Returns True when it is safe to go on.
function CloseRunningApp(const EventName, Action: String): Boolean;
var
  Msg: String;
begin
  Result := True;
  while AppIsRunning do
  begin
    if AskAppToExit(EventName) then Exit;
    if OldVersionRunning then
      Msg := 'An older GameTimer is running, so it can''t be ' + Action + ' yet.' + #13#10#13#10 +
             'Exit it from its tray icon (right-click it, Exit), then click Retry.'
    else
      Msg := 'GameTimer is still running, so it can''t be ' + Action + ' yet.' + #13#10#13#10 +
             'Confirm closing it in the Windows Hello prompt (it may be behind this window), ' +
             'or exit it from its tray icon. Then click Retry.';
    if SuppressibleMsgBox(Msg, mbError, MB_RETRYCANCEL, IDCANCEL) <> IDRETRY then
    begin
      Result := False;
      Exit;
    end;
  end;
end;

// Starts the app again as the user who ran Setup, if it was running before and we closed it.
procedure RelaunchIfNeeded(AsOriginalUser: Boolean);
var
  Code: Integer;
begin
  if not AppWasRunning or AppIsRunning or not FileExists(AppExe) then Exit;
  if AsOriginalUser then
    ExecAsOriginalUser(AppExe, '', '', SW_SHOWNORMAL, ewNoWait, Code)
  else
    Exec(AppExe, '', '', SW_SHOWNORMAL, ewNoWait, Code);
end;

// ---------- Setup ----------

procedure InitializeWizard;
begin
  ProgressPage := CreateOutputProgressPage('Closing GameTimer', '');
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  AppWasRunning := AppIsRunning;
  if not CloseRunningApp(UpdateEventName, 'updated') then
    Result := 'GameTimer is still running. Close it and run Setup again.';
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then Finished := True;
end;

procedure DeinitializeSetup;
begin
  // Install failed or was cancelled after the app had already closed: don't leave the PC without it.
  // (After a successful install the [Run] entry starts it.)
  if not Finished then
    try
      RelaunchIfNeeded(True);
    except
    end;
  ReleaseWaitingMutex;
end;

// ---------- Uninstall ----------

function InitializeUninstall: Boolean;
begin
  AppWasRunning := AppIsRunning;
  Result := CloseRunningApp(RemoveEventName, 'uninstalled');
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then Finished := True;
end;

procedure DeinitializeUninstall;
begin
  // The user answered No to "remove GameTimer?" after we had closed it: start it again
  if not Finished then
    try
      RelaunchIfNeeded(False);
    except
    end;
  ReleaseWaitingMutex;
end;
