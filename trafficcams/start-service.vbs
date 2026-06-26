Set objShell = CreateObject("WScript.Shell")
Set objFSO = CreateObject("Scripting.FileSystemObject")

' Get the directory where this script is located
strScriptPath = objFSO.GetAbsolutePathName(WScript.ScriptFullName)
strScriptDir = objFSO.GetParentFolderName(strScriptPath)

' Path to the batch file
strBatchFile = strScriptDir & "\start-service.bat"

' Run the batch file silently (hidden window)
objShell.Run strBatchFile, 0, False

' Log the startup
Dim objFile, strLogPath, strLogMessage
strLogPath = strScriptDir & "\logs\startup.log"

If Not objFSO.FolderExists(strScriptDir & "\logs") Then
    objFSO.CreateFolder(strScriptDir & "\logs")
End If

Set objFile = objFSO.CreateTextFile(strLogPath, True)
objFile.WriteLine Now & " - Traffic Cams Server started via VBScript"
objFile.Close()
