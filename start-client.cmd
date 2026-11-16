@echo off
setlocal
set "LTALK_JAR=%~dp0ltalk-1.0.0.jar"
if not exist "%LTALK_JAR%" set "LTALK_JAR=%~dp0target\ltalk-1.0.0.jar"
if not exist "%LTALK_JAR%" goto missing_jar

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" (
    start "" "%JAVA_HOME%\bin\javaw.exe" -cp "%LTALK_JAR%" cn.ltalk.client.ChatWindow %*
    exit /b
)
where.exe javaw.exe >nul 2>&1
if errorlevel 1 goto missing_java
start "" javaw.exe -cp "%LTALK_JAR%" cn.ltalk.client.ChatWindow %*
exit /b

:missing_jar
echo LTalk JAR was not found. Build LTalk or place ltalk-1.0.0.jar next to this file.
pause
exit /b 1

:missing_java
echo Java was not found. Install Java 17 or newer and set JAVA_HOME or add Java to PATH.
pause
exit /b 1
