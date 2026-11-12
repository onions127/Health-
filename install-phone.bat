@echo off
echo Installing Health++ Redesign v2 over existing com.jcwear.coach on phone...
"C:\Users\stav\AppData\Local\Android\Sdk\platform-tools\adb.exe" wait-for-device
"C:\Users\stav\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r "d:\projects\JCWear Pro alternative\android-app\app\build\outputs\apk\debug\app-debug.apk"
"C:\Users\stav\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell am start -n com.jcwear.coach/.MainActivity
echo Done!
pause
