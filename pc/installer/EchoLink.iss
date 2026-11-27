; EchoLink 安装器（Inno Setup）
;
; 设计要点：
; 1. 单文件 exe，双击即可安装。
; 2. **按需安装 .NET**：检测到系统已有 .NET 8 桌面运行时就直接跳过，
;    没有才静默安装随包携带的运行时安装器。已经装了的用户不会白白多等几分钟。
; 3. 桌面快捷方式做成可选项（安装向导里的复选框）。
; 4. 应用目录放在 Program Files，卸载信息写进「应用和功能」。

#define AppName "EchoLink"
#ifndef AppVersion
  #define AppVersion "0.7.0"
#endif
#define AppPublisher "EchoLink"
#define AppExe "EchoLink.App.WinUI.exe"

[Setup]
AppId={{9E4B7C31-2F5A-4C88-9D61-3A7E5B2C4D18}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=EchoLink-Setup-{#AppVersion}
SetupIconFile=app.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
; x64 only：WinUI 版只发布了 win-x64
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; 安装 .NET 运行时需要管理员权限
PrivilegesRequired=admin
; 升级安装时若程序正开着，提示关闭而不是失败
CloseApplications=yes
RestartApplications=no
MinVersion=10.0.17763

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务："

[Files]
; 应用本体（自包含的 Windows App SDK；.NET 走系统运行时，见 [Code]）
Source: "..\..\artifacts\pc-winui\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; .NET 运行时安装器：dontcopy 表示不落地，只在需要时由代码临时展开执行
Source: "runtime\windowsdesktop-runtime-8-win-x64.exe"; Flags: dontcopy

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\卸载 {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "立即运行 {#AppName}"; \
    Flags: nowait postinstall skipifsilent

[Code]
const
  RuntimeKey = 'SOFTWARE\dotnet\Setup\InstalledVersions\x64\sharedfx\Microsoft.WindowsDesktop.App';
  RuntimeExe = 'windowsdesktop-runtime-8-win-x64.exe';

{ 判断是否已装 .NET 8 桌面运行时。
  这个键下每个已安装版本是一个「值名」（例如 8.0.30、10.0.11），
  所以只要存在以 "8." 开头的值就认为满足。
  64 位与 32 位视图都查：不同安装方式写入的位置不一样。 }
function HasDotNet8Desktop(): Boolean;
var
  Names: TArrayOfString;
  i: Integer;
begin
  Result := False;

  if RegGetValueNames(HKLM64, RuntimeKey, Names) then
    for i := 0 to GetArrayLength(Names) - 1 do
      if Pos('8.', Names[i]) = 1 then
      begin
        Result := True;
        Exit;
      end;

  if RegGetValueNames(HKLM32, RuntimeKey, Names) then
    for i := 0 to GetArrayLength(Names) - 1 do
      if Pos('8.', Names[i]) = 1 then
      begin
        Result := True;
        Exit;
      end;
end;

procedure InitializeWizard();
begin
  if not HasDotNet8Desktop() then
    WizardForm.WelcomeLabel2.Caption :=
      '将在这台电脑上安装 EchoLink。' + #13#10#13#10 +
      '检测到系统中没有 .NET 8 桌面运行时，安装程序会自动为它装好（约 1–2 分钟），无需你手动操作。';
end;

{ 在拷贝文件**之前**把 .NET 备齐：
  - 已有 .NET 8 就直接跳过，用户完全无感；
  - 缺失则静默安装随包的运行时；
  - 安装失败就中止 setup 并说明原因，避免装完却打不开。 }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
  RuntimePath: String;
begin
  Result := '';

  if HasDotNet8Desktop() then
    Exit;

  try
    ExtractTemporaryFile(RuntimeExe);
  except
    Result := '无法展开 .NET 运行时安装包，安装中止。';
    Exit;
  end;

  RuntimePath := ExpandConstant('{tmp}\' + RuntimeExe);

  if not Exec(RuntimePath, '/install /quiet /norestart', '', SW_SHOW,
    ewWaitUntilTerminated, ResultCode) then
  begin
    Result := '无法启动 .NET 8 桌面运行时安装程序，安装中止。';
    Exit;
  end;

  { 0 = 成功；3010 = 成功但需要重启；其他都视为失败 }
  if (ResultCode <> 0) and (ResultCode <> 3010) then
    Result := '安装 .NET 8 桌面运行时失败（退出码 ' + IntToStr(ResultCode) + '）。' + #13#10 +
      '可以手动安装后重新运行本安装程序：https://dotnet.microsoft.com/download/dotnet/8.0';
end;
