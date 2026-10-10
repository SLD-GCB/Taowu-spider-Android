@echo off
setlocal
set "PATH=%SystemRoot%\System32;%SystemRoot%;%PATH%"
cd /d "%~dp0"

echo ============================================
echo   梼杌 (taowu-android) - 打包 APK
echo ============================================
echo.

REM ================= 环境（按需改）=================
set "JAVA_HOME=%USERPROFILE%\.jdks\temurin-26.0.2.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
REM Android SDK 走 local.properties 里的 sdk.dir
REM ================================================

if not exist "gradlew.bat" (
  echo [错误] 找不到 gradlew.bat，请把本脚本放在项目根目录运行。
  goto :done
)

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [提示] 没在 %JAVA_HOME% 找到 java，改试系统 PATH 里的 java。
)

echo [1/3] 清理旧产物...
if exist "build\outputs\apk\debug" rmdir /s /q "build\outputs\apk\debug" 2>nul

echo [2/3] 编译打包（首次会下载依赖，较慢）...
call gradlew.bat assembleDebug --console=plain
if errorlevel 1 (
  echo.
  echo [失败] 构建出错，请把上面的日志发出来。
  goto :done
)

set "APK=build\outputs\apk\debug\taowu-android-debug.apk"
if not exist "%APK%" (
  echo [失败] 没找到 APK：%APK%
  goto :done
)

echo [3/3] 复制到 dist...
if not exist "dist" mkdir "dist"
copy /y "%APK%" "dist\梼杌-debug.apk" >nul

echo.
echo ============================================
echo   完成
echo   APK: %CD%\dist\梼杌-debug.apk
echo.
echo   装到真机二选一：
echo     1. 数据线加 adb：adb install -r "dist\梼杌-debug.apk"
echo     2. 把 dist\梼杌-debug.apk 拷到手机点开安装（需允许未知来源）
echo ============================================
echo.

:done
echo.
pause
endlocal
