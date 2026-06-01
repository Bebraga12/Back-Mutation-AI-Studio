@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "APP_ROOT=%SCRIPT_DIR%..\"
set "JAR_PATH=%APP_ROOT%app\mutation-ai-studio.jar"

if not exist "%JAR_PATH%" (
  echo Mutation AI Studio nao encontrado em "%JAR_PATH%".
  echo Execute scripts\windows\install.ps1 para instalar o pacote.
  exit /b 1
)

if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" %MUTATION_AI_OPTS% -jar "%JAR_PATH%" %*
exit /b %ERRORLEVEL%
