param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
# Use synthetic data only. Never print credentials, request headers or raw errors.
$settings = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $ProjectRoot 'local.properties')) {
    if ($line -match '^\s*(DEEPSEEK_API_KEY|DEEPSEEK_MODEL)\s*=(.*)$') { $settings[$matches[1]] = $matches[2].Trim() }
}
if (-not $settings['DEEPSEEK_API_KEY']) { Write-Output 'Missing DeepSeek key'; exit 1 }
$model = $settings['DEEPSEEK_MODEL']
if (-not $model) { $model = 'deepseek-flash' }
$source = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot 'app/src/main/java/tw/driver/schedule/AiBookingParser.kt')
$instructionMatch = [regex]::Match($source, 'private val instructions = """([\s\S]*?)"""')
if (-not $instructionMatch.Success) { Write-Output 'Cannot locate extraction instructions'; exit 1 }
$fields = @('date','pickupTime','customer','contact','passengerPhone','contactPhone','pickup','destination','fare','returnTime','notes','calendarTime','uncertainties')
$flags = @('tentative','singleTrip','timeFlexible')
$properties = @{}
foreach ($field in $fields) { $properties[$field] = @{type='STRING'} }
foreach ($field in $flags) { $properties[$field] = @{type='BOOLEAN'} }
$schema = @{type='OBJECT'; required=@('imageTranscript','orders'); properties=@{
    imageTranscript=@{type='STRING'}
    orders=@{type='ARRAY'; items=@{type='OBJECT'; properties=$properties; required=($fields + $flags)}}}} | ConvertTo-Json -Depth 20 -Compress
$body = @{
    model=$model; thinking=@{type='disabled'}; stream=$false; max_tokens=8192
    response_format=@{type='json_object'}
    messages=@(
        @{role='system'; content=($instructionMatch.Groups[1].Value + "`n請輸出 JSON，完整符合以下欄位規格（不得省略欄位）：`n" + $schema)}
        @{role='user'; content='匿名測試，9/21 10:00–10:30都可，測試聯絡人的爸爸從新北市永和區民生路21號到臺安醫院，回程13:00左右；日曆10:00–13:10，年份未提供。總額180，補助126，自付54。'}
    )
} | ConvertTo-Json -Depth 20 -Compress
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(15)
$request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, 'https://api.deepseek.com/chat/completions')
$request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $settings['DEEPSEEK_API_KEY'])
$request.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    Write-Output "HTTP $([int]$response.StatusCode); elapsed $([Math]::Round($timer.Elapsed.TotalSeconds, 1)) seconds"
    if (-not $response.IsSuccessStatusCode) { exit 2 }
    $result = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ($result.choices[0].finish_reason -ne 'stop') { Write-Output 'Incomplete completion'; exit 3 }
    $parsed = $result.choices[0].message.content | ConvertFrom-Json
    if ($parsed.imageTranscript -isnot [string]) { throw 'Invalid transcript' }
    foreach ($order in $parsed.orders) {
        foreach ($field in $fields) { if ($order.$field -isnot [string]) { throw 'Invalid string field' } }
        foreach ($field in $flags) { if ($order.$field -isnot [bool]) { throw 'Invalid boolean field' } }
    }
    $checks = @{
        orders=$parsed.orders.Count
        schemaValid=$true
        rangePreserved=(@($parsed.orders | Where-Object { $_.pickupTime -eq '10:00–10:30' }).Count -gt 0)
        returnTimeExtracted=(@($parsed.orders | Where-Object { -not $_.singleTrip -and $_.returnTime -eq '13:00' }).Count -gt 0)
        fareExtracted=(@($parsed.orders | Where-Object { $_.fare -eq '自付額 54' }).Count -gt 0)
    }
    $checks | ConvertTo-Json -Compress
    if ($checks.orders -ne 1 -or -not $checks.rangePreserved -or -not $checks.returnTimeExtracted -or -not $checks.fareExtracted) { exit 4 }
} catch {
    Write-Output "Smoke test did not complete or validate within 15 seconds. Error details omitted to protect credentials."
    exit 5
} finally {
    $request.Dispose(); $client.Dispose()
}
