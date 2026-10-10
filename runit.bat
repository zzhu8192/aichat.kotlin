@echo off
chcp 65001 >nul
set KOTLLM_CONFIG_DIR=%~dp0config

rem Run a private copy of the jar, not target\kotllm.jar itself.
rem
rem `java -jar` keeps the jar open, memory-maps its directory and loads classes
rem lazily, so `mvn package` during a session pulls the file out from under it:
rem the next class needed for the first time is simply gone
rem (NoClassDefFoundError, classically LineReaderImpl$BellType, which is only
rem touched on the first terminal bell). Copying first makes a rebuild
rem harmless. Nothing depends on where the jar lives -- the classpath handed to
rem the interpreter comes from java.class.path and the dependency manifest from
rem a resource inside the jar.
set KOTLLM_RUN_JAR=%TEMP%\kotllm-run-%RANDOM%%RANDOM%.jar
copy /y "%~dp0target\kotllm.jar" "%KOTLLM_RUN_JAR%" >nul
if errorlevel 1 (
    echo runit: could not copy target\kotllm.jar -- build it with: mvn -q clean package 1>&2
    exit /b 1
)

java -Dfile.encoding=UTF-8 -jar "%KOTLLM_RUN_JAR%" %*
set KOTLLM_EXIT=%ERRORLEVEL%

del "%KOTLLM_RUN_JAR%" >nul 2>&1
exit /b %KOTLLM_EXIT%
