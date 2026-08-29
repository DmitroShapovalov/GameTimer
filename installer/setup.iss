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
Filename: "{app}\SystemMonitor.exe"; Description: "Launch GameTimer"; Flags: nowait postinstall skipifsilent
