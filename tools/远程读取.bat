@echo off
chcp 65001 >nul
title 远程正式服读取
setlocal enabledelayedexpansion

echo ============================================
echo   远程正式服读取（拉 config/kjbb + 日志）
echo ============================================
echo.

set /p HOST=服务器 IP ：
if "%HOST%"=="" goto :abort

set /p USER=用户名（root / ubuntu）：
if "%USER%"=="" goto :abort

set /p PORT=SSH 端口（直接回车 = 22）：
if "%PORT%"=="" set PORT=22

echo.
echo 开始连接 %USER%@%HOST%:%PORT% ...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0远程读取.ps1" -SshHost "%HOST%" -User "%USER%" -Port %PORT%

echo.
echo ============================================
echo   完成。把上面的本地目录路径发给我即可。
echo ============================================
pause
exit /b 0

:abort
echo 已取消。
pause
exit /b 1
