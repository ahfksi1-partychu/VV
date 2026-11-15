# 개발 빌드 측정 음성을 에뮬레이터(차량) 앱 내부 저장소에서 F:\MyCarHub\measurements 로 복사하고 결과를 요약한다.
# USB/adb 로컬 복사만 하며 외부 서버·클라우드로 보내지 않는다. measurements 폴더는 .gitignore 로 Git 에서 제외된다.
#
#   .\tools\pull_measurements.ps1                # 복사 + 요약
#   .\tools\pull_measurements.ps1 -SummaryOnly   # PC 사본 요약만
#   .\tools\pull_measurements.ps1 -DeletePcCopy  # PC 사본 삭제 (확인 입력 필요). 기기 쪽은 앱 측정 화면에서 삭제
param([switch]$SummaryOnly, [switch]$DeletePcCopy)

$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$dest = Join-Path (Split-Path $PSScriptRoot -Parent) 'measurements'

if ($DeletePcCopy) {
    if (-not (Test-Path $dest)) { 'PC 사본이 없습니다.'; return }
    $answer = Read-Host "$dest 의 측정 음성을 모두 삭제하려면 '삭제'를 입력하세요"
    if ($answer -ne '삭제') { '취소했습니다.'; return }
    Remove-Item -Recurse -Force $dest
    'PC 사본을 삭제했습니다.'
    return
}

if (-not $SummaryOnly) {
    New-Item -ItemType Directory -Force $dest | Out-Null
    $tar = Join-Path $dest 'device.tar'
    $paths = "for p in files/measurements files/wakeword/samples; do [ -e `$p ] && echo `$p; done"
    $existing = (& $adb shell "run-as com.mycarhub --user 10 sh -c '$paths'") -join ' '
    if (-not $existing.Trim()) { '기기에 측정 데이터가 없습니다.'; return }
    cmd /c "`"$adb`" exec-out run-as com.mycarhub --user 10 tar -cf - $existing > `"$tar`""
    tar.exe -xf $tar -C $dest
    Remove-Item $tar
    "복사 완료: $dest"
}

$sessions = Get-ChildItem (Join-Path $dest 'files\measurements') -Directory -ErrorAction SilentlyContinue
if (-not $sessions) { '측정 세션이 없습니다.'; return }
$rows = foreach ($s in $sessions) {
    $meta = Get-Content (Join-Path $s.FullName 'meta.json') -Raw -Encoding UTF8 -ErrorAction SilentlyContinue | ConvertFrom-Json
    if (-not $meta) { continue }
    [pscustomobject]@{
        세션 = $s.Name; 조건 = $meta.conditionLabel; 기대 = $(if ($meta.expectWakeWord) { '감지' } else { '무시' })
        '길이(초)' = [math]::Round($meta.durationMs / 1000); 시도 = $meta.attempts; 감지 = $meta.detectionCount
        호출어 = $meta.model.name; 기준 = [math]::Round($meta.model.threshold, 3)
    }
}
$rows | Format-Table -AutoSize
$rows | Group-Object 조건 | ForEach-Object {
    $a = ($_.Group | Measure-Object 시도 -Sum).Sum
    $d = ($_.Group | Measure-Object 감지 -Sum).Sum
    $expect = $_.Group[0].기대
    if ($expect -eq '감지') { "{0}: 시도 {1} / 감지 {2} ({3:N1}%)" -f $_.Name, $a, $d, $(if ($a) { 100 * $d / $a } else { 0 }) }
    else { "{0}: 오인식 {1}회 (총 {2:N1}분)" -f $_.Name, $d, (($_.Group | Measure-Object '길이(초)' -Sum).Sum / 60) }
}
$mb = (Get-ChildItem $dest -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
"PC 사본 용량: {0:N1} MB ({1})" -f $mb, $dest
