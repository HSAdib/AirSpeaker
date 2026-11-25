; ====================================================================
; AirSpeaker - Inno Setup 6 Script (Native Windows Installed App)
; ====================================================================

#define MyAppName "AirSpeaker"
#ifndef MyAppVersion
#define MyAppVersion "1.0.0"
#endif
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
DefaultDirName={localappdata}\Programs\AirSpeaker
DefaultGroupName=AirSpeaker
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..
OutputBaseFilename=AirSpeaker-Setup
SetupIconFile=vendor\app_icon.ico
UninstallDisplayIcon={app}\AirSpeaker.exe
UninstallDisplayName=AirSpeaker
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
Name: "{group}\AirSpeaker"; Filename: "{app}\AirSpeaker.exe"
Name: "{group}\{cm:UninstallProgram,AirSpeaker}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\AirSpeaker"; Filename: "{app}\AirSpeaker.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\AirSpeaker.exe"; Description: "{cm:LaunchProgram,AirSpeaker}"; Flags: nowait postinstall skipifsilent
