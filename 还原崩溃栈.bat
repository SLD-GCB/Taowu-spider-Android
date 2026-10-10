@echo off
setlocal
set "PATH=%SystemRoot%\System32;%SystemRoot%;%PATH%"
cd /d "%~dp0"

echo ============================================
echo   梼杌 (taowu-android) - 还原崩溃栈
echo   （把 release 包混淆后的崩溃日志翻回可读的类名/行号）
echo ============================================
echo.

REM ================= 环境（按需改）=================
set "JAVA_HOME=%USERPROFILE%\.jdks\temurin-26.0.2.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "JAVA=java"
if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
REM ================================================

REM ---- 1) 崩溃日志：支持把文件拖到本脚本上，否则用项目根 crash.txt ----
set "CRASH=%~1"
if "%CRASH%"=="" set "CRASH=crash.txt"
if not exist "%CRASH%" (
  echo [错误] 找不到崩溃日志文件：%CRASH%
  echo.
  echo 用法二选一：
  echo   1. 把崩溃日志保存为 crash.txt 放到本目录，再双击本脚本；
  echo   2. 直接把崩溃日志文件拖到本脚本图标上。
  goto :done
)
echo [1/3] 崩溃日志：%CRASH%

REM ---- 2) mapping 映射表：优先固定名，否则 dist 下最新的 mapping-*.txt ----
set "MAP=dist\梼杌-release-mapping.txt"
if exist "%MAP%" goto :gotmap
set "MAP="
for /f "delims=" %%f in ('dir /b /o-d "dist\mapping-*.txt" 2^>nul') do (
  set "MAP=dist\%%f"
  goto :gotmap
)
:gotmap
if not exist "%MAP%" (
  echo [错误] 没找到 mapping 映射表（应在 dist 目录下）。
  echo        先双击 打包Release.bat 生成带 mapping 的正式包。
  goto :done
)
echo [2/3] 映射表：%MAP%

REM ---- 3) 找 retrace 工具（在各版本 build-tools 的 lib\d8.jar 里）----
set "BT=%ANDROID_HOME%\build-tools"
if not defined ANDROID_HOME set "BT=%LOCALAPPDATA%\Android\Sdk\build-tools"
set "D8="
for /f "delims=" %%d in ('dir /b /o-n "%BT%" 2^>nul') do (
  if exist "%BT%\%%d\lib\d8.jar" (
    set "D8=%BT%\%%d\lib\d8.jar"
    goto :gotd8
  )
)
:gotd8
if not defined D8 (
  echo [错误] 没找到 d8.jar（retrace 工具在 Android SDK 的 build-tools 里）。
  echo        请确认已安装 build-tools，或设好 ANDROID_HOME 环境变量。
  goto :done
)
echo [3/3] retrace：%D8%
echo.

echo 正在还原...
"%JAVA%" -cp "%D8%" com.android.tools.r8.retrace.Retrace "%MAP%" "%CRASH%" > "堆栈还原结果.txt" 2>&1

echo.
echo ----------------- 还原结果 -----------------
type "堆栈还原结果.txt"
echo -------------------------------------------
echo.
echo 结果已保存：%CD%\堆栈还原结果.txt
echo （对照 mapping.txt 里的「原始行号」，括号里的行号即源码行）

:done
echo.
pause
endlocal
