#define MyAppName "MoonView"
#define MyAppVersion "1.0.20"
#define MyAppPublisher "MoonShare"
#define MyAppExeName "MoonView.exe"
#define MyAppId "{{7C3B5A1E-9E4B-4A7A-9C8E-6D2F3A1B5C70}"

[Setup]
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName=D:\MoonShare\pc\MoonView
DefaultGroupName=MoonShare
DisableProgramGroupPage=yes
OutputDir=D:\MoonShare\installer
OutputBaseFilename=MoonView-1.0.20-Setup
SetupIconFile=C:\Users\lenovo\Documents\deepseek-harness\default-workspace\MoonShare\pc\MoonView\Assets\moonview.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
MinVersion=10.0.17763
CloseApplications=yes
CloseApplicationsFilter={#MyAppExeName}
RestartApplications=no
VersionInfoVersion=1.0.20.0
VersionInfoCompany=MoonShare
VersionInfoDescription=MoonView 1.0.20 Setup
VersionInfoProductName=MoonView
VersionInfoProductVersion=1.0.20

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Files]
Source: "D:\MoonShare\pc\MoonView-selfcontained\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "D:\MoonShare\installer\README-zh.txt"; DestDir: "{app}"; DestName: "README-zh.txt"; Flags: ignoreversion
Source: "D:\MoonShare\toolchain\MicrosoftEdgeWebview2Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Icons]
Name: "{group}\MoonView"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall MoonView"; Filename: "{uninstallexe}"
Name: "{autodesktop}\MoonView"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\App Paths\{#MyAppExeName}"; ValueType: string; ValueName: ""; ValueData: "{app}\{#MyAppExeName}"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\App Paths\{#MyAppExeName}"; ValueType: string; ValueName: "Path"; ValueData: "{app}"; Flags: uninsdeletekey

[Run]
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Installing Microsoft Edge WebView2 Runtime..."; Flags: waituntilterminated; Check: not WebView2RuntimeInstalled
Filename: "{app}\{#MyAppExeName}"; Description: "Launch MoonView"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}\web"
Type: filesandordirs; Name: "{app}\runtimes"
Type: files; Name: "{app}\MoonView.exe"

[Code]
function WebView2RuntimeInstalled: Boolean;
begin
  Result :=
    RegKeyExists(HKLM, 'SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}') or
    RegKeyExists(HKLM, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}') or
    RegKeyExists(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}');
end;

procedure InitializeWizard;
begin
  if not WebView2RuntimeInstalled then
  begin
    MsgBox('MoonView needs Microsoft Edge WebView2 Runtime.' + #13#10 +
      'The installer will run the official WebView2 bootstrapper (Internet required).',
      mbInformation, MB_OK);
  end;
end;
