@echo off
setlocal
set "PATH=%SystemRoot%\System32;%SystemRoot%;%PATH%"
cd /d "%~dp0"

echo ============================================
echo   梼杌 (taowu-android) - 打包 Release 正式版
echo ============================================
echo.

REM ================= 环境（按需改）=================
set "JAVA_HOME=%USERPROFILE%\.jdks\temurin-26.0.2.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
REM Android SDK 走 local.properties 里的 sdk.dir
REM 签名信息在 keystore.properties（含 keystore 路径与密码）
REM ================================================

if not exist "gradlew.bat" (
  echo [错误] 找不到 gradlew.bat，请把本脚本放在项目根目录运行。
  goto :done
)

if not exist "keystore.properties" (
  echo [错误] 找不到 keystore.properties，release 无法签名。
  goto :done
)

echo [1/4] 编译打包 release（R8 混淆 + 资源压缩）...
call gradlew.bat assembleRelease --console=plain
if errorlevel 1 (
  echo.
  echo [失败] 构建出错，请把上面的日志发出来。
  goto :done
)

set "APK=build\outputs\apk\release\taowu-android-release.apk"
if not exist "%APK%" (
  set "APK=build\outputs\apk\release\app-release.apk"
)
if not exist "%APK%" (
  echo [失败] 没找到 release APK，去 build\outputs\apk\release\ 看看实际文件名。
  goto :done
)

if not exist "dist" mkdir "dist"

echo [2/4] 复制 APK 到 dist...
copy /y "%APK%" "dist\梼杌-release.apk" >nul

echo [3/4] 复制混淆映射表到 dist（用于还原崩溃栈）...
set "MAP=build\outputs\mapping\release\mapping.txt"
if not exist "%MAP%" goto :nomap
copy /y "%MAP%" "dist\梼杌-release-mapping.txt" >nul
set "TS="
for /f "delims=" %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss" 2^>nul') do set "TS=%%i"
if not defined TS set "TS=%RANDOM%"
copy /y "%MAP%" "dist\mapping-%TS%.txt" >nul
echo   dist\mapping-%TS%.txt   （另存一份固定名 dist\梼杌-release-mapping.txt）
goto :sign
:nomap
echo [提示] 没找到 mapping.txt（若未开混淆属正常）。
:sign

echo [4/4] 校验签名...
set "BT=%ANDROID_HOME%\build-tools"
if not defined ANDROID_HOME set "BT=%LOCALAPPDATA%\Android\Sdk\build-tools"
for /f "delims=" %%d in ('dir /b /o-n "%BT%" 2^>nul') do (
  set "APKSIGNER=%BT%\%%d\apksigner.bat"
  goto :sign2
)
:sign2
if exist "%APKSIGNER%" (
  call "%APKSIGNER%" verify --print-certs "dist\梼杌-release.apk"
) else (
  echo [提示] 没找到 apksigner，跳过签名校验。APK 已产出。
)

echo.
echo ============================================
echo   完成
echo   Release APK: %CD%\dist\梼杌-release.apk
echo   映射表:      %CD%\dist\梼杌-release-mapping.txt
echo   发布时请把该版本的 mapping 一起存档（否则线上崩溃无法还原）。
echo ============================================
echo.

:done
echo.
pause
endlocal
