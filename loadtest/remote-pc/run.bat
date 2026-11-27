@echo off
rem Run JMeter only, on another PC (Windows). Only this folder is needed.
rem
rem   run.bat EXAMPLE_SERVER_IP CONCERT_ID HH:MM [users=5000] [member offset=5000]
rem   e.g.  run.bat 172.30.71.30 12 19:42
rem
rem CONCERT_ID and HH:MM must be the same as on the FairChain PC. Offset 5000 because the FairChain PC uses members 0-4999.
rem Optional: set VERIFY=120 / set WAIT_MS=120000 / set CONNECT_MS=20000 / set STAY=0 before running.
setlocal
cd /d "%~dp0"
set HOST=%1
set CONCERT=%2
set AT=%3
set USERS=%4
set BASE=%5
if "%AT%"=="" (
  echo usage: run.bat EXAMPLE_SERVER_IP CONCERT_ID HH:MM [users=5000] [member offset=5000]
  exit /b 1
)
if "%USERS%"=="" set USERS=5000
if "%BASE%"=="" set BASE=5000
if "%VERIFY%"=="" set VERIFY=0
if "%WAIT_MS%"=="" set WAIT_MS=120000
if "%CONNECT_MS%"=="" set CONNECT_MS=20000
if "%STAY%"=="" set STAY=0
where jmeter >nul 2>nul
if errorlevel 1 (
  echo jmeter not found. Install JMeter 5.6 and add its bin folder to PATH.
  exit /b 1
)
for /f %%i in ('powershell -NoProfile -Command "[DateTimeOffset]::new((Get-Date '%AT%')).ToUnixTimeMilliseconds()"') do set ENTER=%%i
for /f %%i in ('powershell -NoProfile -Command "[int]((Get-Date '%AT%') - (Get-Date)).TotalSeconds"') do set LEFT=%%i
if "%ENTER%"=="" (
  echo could not read the time: %AT%
  exit /b 1
)
if %LEFT% LSS 40 (
  echo only %LEFT% seconds left until %AT%. Give a time at least 1 minute ahead.
  exit /b 1
)
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format HHmmss"') do set STAMP=%%i
set OUT=results\%STAMP%
mkdir "%OUT%"
echo server %HOST% / concert #%CONCERT% / %USERS% users (offset %BASE%) / enter at %AT% (in %LEFT% s)
set JVM_ARGS=-Xms1g -Xmx3g -Xss256k
call jmeter -n -t fairticket.jmx -Jhttpclient4.time_to_live=3600000 -Jhost=%HOST% -JstayMs=%STAY% -JresponseTimeout=%WAIT_MS% -JconnectTimeout=%CONNECT_MS% -JverifyMax=%VERIFY% -Jusers=%USERS% -JconcertId=%CONCERT% -Jseats=0 -JrampUp=20 -Jpremade=true -JuserOffset=%BASE% -JraceUsers=0 -JmacroUsers=0 -Jtag=r1 -JenterAt=%ENTER% -j "%OUT%\jmeter.log" -l "%OUT%\remote.jtl"
where python >nul 2>nul && python summarize.py "%OUT%\remote.jtl"
echo result file: %cd%\%OUT%\remote.jtl
endlocal
