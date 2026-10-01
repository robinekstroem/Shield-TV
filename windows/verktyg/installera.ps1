# Tv för Windows: bygger och installerar appen på den här datorn.
#
# Startas av "Installera Tv.cmd" i projektmappen. Hämtar det som behövs för
# bygget (Java, Gradle, VLC) till en egen mapp, bygger Tv och installerar den
# för den inloggade användaren. Inget installeras i Windows utöver Tv själv.
#
# Går att köra igen när som helst: den nya versionen ersätter den gamla, och
# källor och bibliotek ligger kvar.
#
# Skriven för Windows PowerShell 5.1, som finns i alla Windows 10 och 11.
# Sparad som UTF-8 med BOM — utan den läser 5.1 å, ä och ö fel.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem

$projekt = Split-Path -Parent $PSScriptRoot                  # mappen windows
$verktyg = Join-Path $env:LOCALAPPDATA 'TvBygg'              # Java, Gradle, bibliotek
$malMapp = Join-Path $env:LOCALAPPDATA 'Programs\Tv'         # där Tv installeras
$logg    = Join-Path $verktyg 'bygg.log'
$loggFel = Join-Path $verktyg 'bygg-fel.log'
$nyckel  = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\se.norrsken.tv'
$start   = Get-Date

function Rubrik([string]$text) {
    Write-Host ''
    Write-Host $text -ForegroundColor Cyan
}

function Info([string]$text) {
    Write-Host "  $text" -ForegroundColor Gray
}

function Avbryt([string]$text) {
    Write-Host ''
    Write-Host $text -ForegroundColor Yellow
    exit 1
}

function Hamta([string]$url, [string]$fil) {
    for ($forsok = 1; $forsok -le 3; $forsok++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $fil
            return
        } catch {
            if ($forsok -eq 3) { throw }
            Info "Nytt försök om fem sekunder ($($_.Exception.Message))"
            Start-Sleep -Seconds 5
        }
    }
}

function PackaUpp([string]$zip, [string]$mal) {
    if (Test-Path $mal) { Remove-Item $mal -Recurse -Force }
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $mal)
}

# Gradles uppgifter i ord, för raden som visar hur långt bygget kommit.
function Steg([string]$uppgift) {
    switch -Wildcard ($uppgift) {
        'syncSharedSources*'   { return 'hämtar koden som delas med Android' }
        'compileKotlin*'       { return 'kompilerar (några minuter)' }
        'prepareAppResources*' { return 'lägger in VLC' }
        'createRuntimeImage*'  { return 'skapar Java-miljön' }
        'createDistributable*' { return 'packar ihop appen' }
        default                { return $uppgift }
    }
}

try {
    Write-Host 'Tv för Windows' -ForegroundColor White
    Info 'Första gången hämtas Java, Gradle, VLC och bibliotek, ungefär 1 GB, och'
    Info 'bygget tar 10-20 minuter. Nästa gång går det betydligt fortare.'

    if (-not [Environment]::Is64BitOperatingSystem) {
        Avbryt 'Tv kräver 64-bitars Windows.'
    }

    # Smart App Control stoppar program som varken är signerade med ett köpt
    # certifikat eller kända av Microsoft, även sådana som byggs på datorn.
    # Säg det direkt, inte efter tjugo minuters bygge.
    $sac = (Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -ErrorAction SilentlyContinue).VerifiedAndReputablePolicyState
    if ($sac -eq 1) {
        Write-Host ''
        Write-Host 'Smart App Control är påslagen i Windows.' -ForegroundColor Yellow
        Info 'Den stoppar program utan köpt signatur, även Tv. Tv byggs och installeras'
        Info 'ändå, men startar först när den är avstängd: sök på Smart App Control i'
        Info 'Start-menyn och välj Av.'
    }
    if (-not (Test-Path (Join-Path $projekt 'build.gradle.kts')) -or -not (Test-Path (Join-Path $projekt '..\app\src\main\java'))) {
        Avbryt ('Hittar inte projektet. Packa upp hela zip-filen först (högerklicka och välj ' +
            'Extrahera alla) och dubbelklicka sedan på "Installera Tv" i den uppackade mappen.')
    }
    New-Item -ItemType Directory -Force -Path $verktyg | Out-Null
    $disk = Get-PSDrive -Name $verktyg.Substring(0, 1)
    if ($disk.Free -lt 4GB) {
        Avbryt 'Det behövs minst 4 GB ledigt utrymme på disken.'
    }

    # --- Java --------------------------------------------------------------
    $jdk = Join-Path $verktyg 'jdk'
    if (Test-Path (Join-Path $jdk 'bin\jpackage.exe')) {
        Info 'Java finns redan.'
    } else {
        Rubrik 'Hämtar Java 21 (ungefär 190 MB)'
        $zip = Join-Path $verktyg 'jdk.zip'
        Hamta 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' $zip
        $tmp = Join-Path $verktyg 'jdk-tmp'
        PackaUpp $zip $tmp
        if (Test-Path $jdk) { Remove-Item $jdk -Recurse -Force }
        Move-Item (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName $jdk
        Remove-Item $tmp, $zip -Recurse -Force
    }

    # --- Gradle ------------------------------------------------------------
    $gradleMapp = Join-Path $verktyg 'gradle'
    $gradle = Get-ChildItem $gradleMapp -Filter 'gradle.bat' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($gradle) {
        Info 'Gradle finns redan.'
    } else {
        Rubrik 'Hämtar Gradle (ungefär 140 MB)'
        $senaste = Invoke-RestMethod -Uri 'https://services.gradle.org/versions/current'
        $zip = Join-Path $verktyg 'gradle.zip'
        Hamta $senaste.downloadUrl $zip
        PackaUpp $zip $gradleMapp
        Remove-Item $zip -Force
        $gradle = Get-ChildItem $gradleMapp -Filter 'gradle.bat' -Recurse | Select-Object -First 1
    }

    # --- VLC ---------------------------------------------------------------
    # Spelarmotorn. Sparas i verktygsmappen, som Java och Gradle, och kopieras
    # in i projektet vid varje bygge: en ny zip-fil packas oftast upp i en ny
    # mapp, och då ska VLC inte behöva hämtas igen.
    $vlcKalla = Join-Path $verktyg 'vlc'
    if (-not (Test-Path (Join-Path $vlcKalla 'libvlc.dll'))) {
        # Den installerade Tv har VLC med sig. Ta den i stället för att hämta.
        $vlcIAppen = Get-ChildItem $malMapp -Filter 'libvlc.dll' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($vlcIAppen -and (Test-Path (Join-Path $vlcIAppen.DirectoryName 'plugins'))) {
            & robocopy.exe $vlcIAppen.DirectoryName $vlcKalla /MIR /NFL /NDL /NJH /NJS /NP /R:2 /W:2 | Out-Null
        }
    }
    if (Test-Path (Join-Path $vlcKalla 'libvlc.dll')) {
        Info 'VLC finns redan.'
    } else {
        Rubrik 'Hämtar VLC (ungefär 45 MB)'
        $klar = $false
        foreach ($version in @('3.0.23', '3.0.21')) {
            try {
                $zip = Join-Path $verktyg 'vlc.zip'
                $tmp = Join-Path $verktyg 'vlc-tmp'
                Hamta "https://download.videolan.org/pub/videolan/vlc/$version/win64/vlc-$version-win64.zip" $zip
                PackaUpp $zip $tmp
                $rot = (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName
                New-Item -ItemType Directory -Force -Path $vlcKalla | Out-Null
                Copy-Item (Join-Path $rot 'libvlc.dll'), (Join-Path $rot 'libvlccore.dll') -Destination $vlcKalla
                Copy-Item (Join-Path $rot 'plugins') -Destination $vlcKalla -Recurse
                # VLC:s egna fönster och visualiseringar används aldrig här.
                foreach ($onodig in @('gui', 'visualization')) {
                    $mapp = Join-Path $vlcKalla "plugins\$onodig"
                    if (Test-Path $mapp) { Remove-Item $mapp -Recurse -Force }
                }
                # Förbyggd plugin-katalog: snabbare första start.
                try { & (Join-Path $rot 'vlc-cache-gen.exe') (Join-Path $vlcKalla 'plugins') | Out-Null } catch { }
                Remove-Item $tmp, $zip -Recurse -Force
                Info "VLC $version följer med appen."
                $klar = $true
                break
            } catch {
                Info "VLC $version gick inte att hämta: $($_.Exception.Message)"
                Remove-Item $vlcKalla -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
        if (-not $klar) {
            Avbryt 'VLC gick inte att hämta. Kontrollera internetanslutningen och försök igen.'
        }
    }
    # Robocopy behåller filtiderna, så VLC:s plugin-katalog gäller även i kopian.
    $vlc = Join-Path $projekt 'app-resources\windows-x64\vlc'
    & robocopy.exe $vlcKalla $vlc /MIR /NFL /NDL /NJH /NJS /NP /R:2 /W:2 | Out-Null
    if ($LASTEXITCODE -ge 8) {
        Avbryt "VLC gick inte att kopiera in i projektet ($vlc)."
    }

    # --- Versionsnummer ----------------------------------------------------
    # Stiger för varje bygge och syns under Inställningar, Om appen.
    $nummerFil = Join-Path $verktyg 'byggnummer.txt'
    $nummer = 1
    if (Test-Path $nummerFil) { $nummer = [int]((Get-Content $nummerFil -Raw).Trim()) + 1 }
    $installerad = (Get-ItemProperty -Path $nyckel -ErrorAction SilentlyContinue).DisplayVersion
    if ($installerad -match '(\d+)$' -and [int]$Matches[1] -ge $nummer) { $nummer = [int]$Matches[1] + 1 }
    $env:TV_BUILD_NUMBER = [string]$nummer

    # --- Bygget ------------------------------------------------------------
    Rubrik 'Bygger Tv'
    Info 'Första gången hämtar Gradle bibliotek en god stund innan raden nedan ändras.'
    $env:JAVA_HOME = $jdk
    $env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
    $env:GRADLE_USER_HOME = Join-Path $verktyg 'g'
    $appMapp = Join-Path $projekt 'build\compose\binaries\main\app\Tv'
    if (Test-Path $appMapp) { Remove-Item $appMapp -Recurse -Force }
    Remove-Item $logg, $loggFel -Force -ErrorAction SilentlyContinue

    $bygge = Start-Process -FilePath $gradle.FullName `
        -ArgumentList 'createDistributable', '--console=plain', '--no-daemon', '--stacktrace' `
        -WorkingDirectory $projekt -NoNewWindow -PassThru `
        -RedirectStandardOutput $logg -RedirectStandardError $loggFel
    $null = $bygge.Handle
    while (-not $bygge.HasExited) {
        Start-Sleep -Seconds 2
        $senaste = Get-Content $logg -Tail 60 -ErrorAction SilentlyContinue |
            Where-Object { $_ -like '> Task :*' } | Select-Object -Last 1
        $vad = if ($senaste) { Steg ($senaste.Substring(8)) } else { 'förbereder' }
        $tid = (Get-Date) - $start
        $rad = '  {0}:{1:00}  {2}' -f [int][Math]::Floor($tid.TotalMinutes), $tid.Seconds, $vad
        if ($rad.Length -gt 72) { $rad = $rad.Substring(0, 72) }
        Write-Host ("`r" + $rad.PadRight(72)) -NoNewline
    }
    Write-Host ''
    $bygge.WaitForExit()
    $kod = $bygge.ExitCode

    if ($kod -ne 0 -or -not (Test-Path (Join-Path $appMapp 'Tv.exe'))) {
        # Samma idé som i Android-bygget: bara de rader som säger vad som gick fel.
        $rader = @()
        foreach ($fil in @($loggFel, $logg)) {
            if (Test-Path $fil) { $rader += @(Get-Content $fil) }
        }
        $fel = @($rader | Where-Object { $_ -like 'e: *' } | Select-Object -First 40)
        if ($fel.Count -eq 0) {
            for ($i = 0; $i -lt $rader.Count; $i++) {
                if ($rader[$i] -like '*What went wrong*') {
                    $fel = @($rader[$i..([Math]::Min($i + 15, $rader.Count - 1))])
                    break
                }
            }
        }
        if ($fel.Count -eq 0) { $fel = @($rader | Select-Object -Last 30) }
        try { Set-Clipboard -Value ("Installera Tv, bygget misslyckades:`r`n" + ($fel -join "`r`n")) } catch { }
        Write-Host ''
        Write-Host 'Bygget misslyckades:' -ForegroundColor Red
        foreach ($felrad in $fel) { Write-Host "  $felrad" -ForegroundColor DarkYellow }
        Write-Host ''
        Write-Host 'Felet är kopierat. Klistra in det i chatten med Ctrl+V.' -ForegroundColor Yellow
        Write-Host "Hela loggen: $logg"
        exit 1
    }

    # --- Installera ----------------------------------------------------------
    Rubrik 'Installerar'
    $igang = Get-Process -Name 'Tv' -ErrorAction SilentlyContinue
    if ($igang) {
        Info 'Stänger Tv för att kunna byta ut den.'
        $igang | Stop-Process -Force
        Start-Sleep -Seconds 2
    }
    New-Item -ItemType Directory -Force -Path $malMapp | Out-Null
    & robocopy.exe $appMapp $malMapp /MIR /NFL /NDL /NJH /NJS /NP /R:2 /W:2 | Out-Null
    if ($LASTEXITCODE -ge 8) {
        Avbryt "Filerna gick inte att kopiera till $malMapp. Är Tv fortfarande igång?"
    }
    $exe = Join-Path $malMapp 'Tv.exe'

    try {
        $skal = New-Object -ComObject WScript.Shell
        foreach ($plats in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) {
            $genvag = $skal.CreateShortcut((Join-Path $plats 'Tv.lnk'))
            $genvag.TargetPath = $exe
            $genvag.WorkingDirectory = $malMapp
            $genvag.IconLocation = "$exe,0"
            $genvag.Description = 'Live TV, filmer och serier'
            $genvag.Save()
        }
    } catch {
        Info "Genvägarna gick inte att skapa: $($_.Exception.Message)"
    }

    # Avinstallation via Inställningar, Appar. Källor och bibliotek lämnas kvar.
    $avinstallera = Join-Path $malMapp 'avinstallera.ps1'
    $skript = @"
# Avinstallerar Tv. Källor och bibliotek lämnas kvar i %APPDATA% och %LOCALAPPDATA%.
`$ErrorActionPreference = 'SilentlyContinue'
Get-Process -Name 'Tv' | Stop-Process -Force
Start-Sleep -Seconds 1
Remove-Item (Join-Path ([Environment]::GetFolderPath('Programs')) 'Tv.lnk') -Force
Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Tv.lnk') -Force
Remove-Item '$nyckel' -Recurse -Force
Set-Location `$env:TEMP
Remove-Item '$malMapp' -Recurse -Force
"@
    [System.IO.File]::WriteAllText($avinstallera, $skript, (New-Object System.Text.UTF8Encoding $true))

    New-Item -Path $nyckel -Force | Out-Null
    $storlek = [int](((Get-ChildItem $malMapp -Recurse -File | Measure-Object -Property Length -Sum).Sum) / 1KB)
    $texter = [ordered]@{
        DisplayName     = 'Tv'
        DisplayVersion  = "1.0.$nummer"
        Publisher       = 'Norrsken'
        DisplayIcon     = "$exe,0"
        InstallLocation = $malMapp
        UninstallString = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$avinstallera`""
    }
    foreach ($namn in $texter.Keys) {
        New-ItemProperty -Path $nyckel -Name $namn -Value $texter[$namn] -PropertyType String -Force | Out-Null
    }
    foreach ($namn in @('NoModify', 'NoRepair')) {
        New-ItemProperty -Path $nyckel -Name $namn -Value 1 -PropertyType DWord -Force | Out-Null
    }
    New-ItemProperty -Path $nyckel -Name 'EstimatedSize' -Value $storlek -PropertyType DWord -Force | Out-Null
    Set-Content -Path $nummerFil -Value $nummer

    $tid = (Get-Date) - $start
    Rubrik 'Klart'
    Write-Host ('  Tv 1.0.{0} är installerad. Den finns i Start-menyn och på skrivbordet.' -f $nummer) -ForegroundColor Green
    Info ('Det tog {0} minuter.' -f [int][Math]::Ceiling($tid.TotalMinutes))
    Info "Byggverktygen ligger kvar i $verktyg, så att nästa uppdatering går fortare."
    Info 'Mappen går att ta bort om du vill frigöra utrymme.'
    try {
        Start-Process -FilePath $exe
    } catch {
        Write-Host ''
        Write-Host 'Tv är installerad, men Windows stoppade den från att starta.' -ForegroundColor Yellow
        Info 'Det är Smart App Control, som stoppar program utan köpt signatur. Sök på'
        Info 'Smart App Control i Start-menyn, välj Av och starta sedan Tv från Start-menyn.'
    }
} catch {
    Write-Host ''
    Write-Host "Något gick fel: $($_.Exception.Message)" -ForegroundColor Red
    try { Set-Clipboard -Value ("Installera Tv: " + $_.Exception.Message + "`r`n" + $_.ScriptStackTrace) } catch { }
    Write-Host 'Felet är kopierat. Klistra in det i chatten med Ctrl+V.' -ForegroundColor Yellow
    exit 1
}
