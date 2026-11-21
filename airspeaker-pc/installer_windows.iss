; ====================================================================
; Air Speaker - Inno Setup 6 Script (Native Windows Installed App)
; ====================================================================

#define MyAppName "Air Speaker"
#define MyAppVersion "1.2.1"
#define MyAppPublisher "Hasan Shahriar Adib"
#define MyAppURL "https://github.com/HSAdib/AirSpeaker"
#define MyAppExeName "AirSpeaker.exe"

[Setup]
AppId={{C82EA3F4-2B4A-48FA-A79B-F27A79D208C9}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={localappdata}\Programs\Air Speaker
DefaultGroupName=Air Speaker
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..
OutputBaseFilename=AirSpeaker-v1-Setup
SetupIconFile=vendor\app_icon.ico
UninstallDisplayIcon={app}\AirSpeaker.exe
UninstallDisplayName=Air Speaker
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=force

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "dist\AirSpeaker\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Air Speaker"; Filename: "{app}\AirSpeaker.exe"
Name: "{group}\{cm:UninstallProgram,Air Speaker}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Air Speaker"; Filename: "{app}\AirSpeaker.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\AirSpeaker.exe"; Description: "{cm:LaunchProgram,Air Speaker}"; Flags: nowait postinstall skipifsilent
