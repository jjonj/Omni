param(
    [Parameter(Mandatory = $true)]
    [string]$Target,
    [string]$FeedFile
)

$ErrorActionPreference = 'Stop'
# This is the existing user-configurable MrSuicideSheep "All Channel Uploads" playlist.
# Leave every other configured music target untouched.
$playlist = 'PLDfKAXSi6kUZnATwAUfN6tg1dULU-7XcD'
if ($Target -notmatch ('(?:[?&])list=' + $playlist + '(?:&|$)')) {
    Write-Output $Target
    exit 0
}

try {
    # Use the channel's public Atom feed rather than a permanently pinned video ID.
    # No YouTube API key or third-party scraping dependency is needed.
    $source = if ($FeedFile) {
        Get-Content -LiteralPath $FeedFile -Raw
    } else {
        (Invoke-WebRequest -Uri 'https://www.youtube.com/feeds/videos.xml?channel_id=UC5nc_ZtjKW1htCVZVRxlQAQ' -UseBasicParsing -TimeoutSec 8).Content
    }
    [xml]$feed = $source
    $ns = New-Object System.Xml.XmlNamespaceManager($feed.NameTable)
    $ns.AddNamespace('atom', 'http://www.w3.org/2005/Atom')
    $ns.AddNamespace('yt', 'http://www.youtube.com/xml/schemas/2015')

    $entries = @($feed.SelectNodes('/atom:feed/atom:entry', $ns) | ForEach-Object {
        $id = $_.SelectSingleNode('yt:videoId', $ns)
        $published = $_.SelectSingleNode('atom:published', $ns)
        if ($null -ne $id -and $id.InnerText -match '^[A-Za-z0-9_-]{11}$' -and $null -ne $published) {
            [pscustomobject]@{ Id = $id.InnerText; Published = [datetimeoffset]::Parse($published.InnerText) }
        }
    })
    $latest = $entries | Sort-Object Published -Descending | Select-Object -First 1
    if ($null -eq $latest) {
        throw 'No valid recent upload in MrSuicideSheep feed.'
    }
    Write-Output ("https://www.youtube.com/watch?v={0}&list={1}&index=1&autoplay=1" -f $latest.Id, $playlist)
} catch {
    Write-Error ("Could not resolve latest MrSuicideSheep upload: " + $_.Exception.Message)
    exit 1
}
