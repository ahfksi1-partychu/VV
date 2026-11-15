# 호출어 자동 평가용 합성 음성 생성 (Windows 내장 한국어 TTS 'Microsoft Heami').
# 실제 사용자 목소리가 아니므로 결과는 참고용이며, 실제 마이크 테스트를 대신하지 않는다.
# 사용: powershell -File tools\make_tts_testset.ps1 [-Out F:\MyCarHub\wakeword-eval\data\tts]
param([string]$Out = (Join-Path $PSScriptRoot '..\wakeword-eval\data\tts'))

Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voice = $synth.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'ko-KR' } | Select-Object -First 1
if (-not $voice) { throw 'Korean TTS voice not installed' }
$synth.SelectVoice($voice.VoiceInfo.Name)
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)

function Say([string]$text, [int]$rate, [string]$file, [int]$volume = 100) {
    New-Item -ItemType Directory -Force (Split-Path $file) | Out-Null
    $synth.Rate = $rate
    $synth.Volume = $volume
    $synth.SetOutputToWaveFile($file, $format)
    $synth.Speak($text)
    $synth.SetOutputToNull()
}

$names = @{
    'bungbung' = @{
        word    = '붕붕아'
        similar = @('붕어야', '봉봉아', '둥둥아', '붕붕', '쿵쿵아', '붕붕이', '뭉뭉아', '방방아', '붐붐아', '부웅', '붕대야', '분분아')
    }
    'ioniq' = @{
        word    = '아이오닉'
        similar = @('아이오늘', '아이고', '아이폰', '아이돌', '아니오', '하이오닉', '다이오드', '아이오아이', '오닉스', '아이 오니까', '아이언맨', '아이 오리')
    }
}

$conversation = @(
    '오늘 날씨가 정말 좋네요', '다음 휴게소에서 잠깐 쉬어 갈까', '엄마한테 전화 좀 걸어 줘', '라디오 볼륨 좀 줄여 줄래',
    '점심은 뭐 먹을까', '이 노래 제목이 뭐였더라', '길이 많이 막히네', '근처에 주유소가 어디 있지',
    '창문 좀 열어도 될까', '아이들 학원 끝나는 시간이 몇 시야', '내일 회의가 몇 시였지', '붕어빵 먹고 싶다',
    '아이스 아메리카노 한 잔 할까', '부산까지 얼마나 걸려', '봉사활동 가는 날이 언제지', '음악 좀 바꿔 줘',
    '에어컨 온도 좀 낮춰', '주차장이 꽉 찼네', '우회전하면 바로 나와', '아이고 깜짝이야',
    '분위기 좋은 카페 알아?', '오늘 저녁에 비 온대', '아이가 잠들었으니까 조용히 하자', '이번 주말에 캠핑 갈래',
    '방금 지나간 차 봤어?', '붕 떠 있는 기분이야', '휴대폰 충전기 어디 있어', '오른쪽 차선으로 바꿔야 해'
)

foreach ($id in $names.Keys) {
    $n = $names[$id]
    $dir = Join-Path $Out $id
    # 등록용 5회: 평소 빠르기 근처에서 조금씩 다르게
    $enrollRates = @(0, -1, 1, 0, -2)
    for ($i = 0; $i -lt 5; $i++) { Say $n.word $enrollRates[$i] (Join-Path $dir "enroll\take_$($i + 1).wav") (90 - 5 * $i) }
    # 정상 호출: 등록과 다른 빠르기 + 문장 속 호출
    $k = 0
    foreach ($rate in @(-4, -3, 2, 3, 4)) { $k++; Say $n.word $rate (Join-Path $dir "positive\solo_$k.wav") }
    $carriers = @("$($n.word) 음악 틀어줘", "저기 $($n.word)", "$($n.word), 집으로 가자", "$($n.word) 에어컨 켜줘", "음 $($n.word)")
    $k = 0
    foreach ($c in $carriers) { $k++; Say $c 0 (Join-Path $dir "positive\phrase_$k.wav") }
    # 비슷한 발음
    $k = 0
    foreach ($w in $n.similar) { $k++; Say $w 0 (Join-Path $dir "similar\similar_$k.wav") }
}

$k = 0
foreach ($s in $conversation) { $k++; Say $s 0 (Join-Path $Out "conversation\talk_$k.wav") }
"generated under $Out"
Get-ChildItem $Out -Recurse -Filter *.wav | Measure-Object Length -Sum | ForEach-Object { "files=$($_.Count) bytes=$($_.Sum)" }
