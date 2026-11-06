package se.norrsken.tv.ipad

import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/**
 * Apples egen spelare i helskärm: AirPlay, bild-i-bild och kontrollerna man
 * känner igen från andra appar. Ljudet fortsätter när skärmen släcks.
 */
internal fun playStream(url: String) {
    val address = NSURL.URLWithString(playableUrl(url)) ?: return
    AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
    val headers = mapOf<Any?, Any?>("User-Agent" to USER_AGENT)
    val asset = AVURLAsset.URLAssetWithURL(address, mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers))
    val player = AVPlayer.playerWithPlayerItem(AVPlayerItem.playerItemWithAsset(asset))
    val controller = AVPlayerViewController()
    controller.player = player
    controller.allowsPictureInPicturePlayback = true
    topController()?.presentViewController(controller, animated = true) { player.play() }
}

private fun topController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (true) {
        val next = top?.presentedViewController ?: break
        top = next
    }
    return top
}

/**
 * Apples spelare spelar HLS men inte direkt-TS. Xtream-servrar levererar
 * samma kanal som HLS om adressen slutar på .m3u8, så sådana adresser byts.
 */
internal fun playableUrl(url: String): String {
    val path = url.substringBefore('?')
    val query = url.substring(path.length)
    if (path.endsWith(".ts", ignoreCase = true) && "/live/" in path) return path.dropLast(3) + ".m3u8" + query
    // Xtream-listor skriver ofta kanalerna som http://server/användare/lösenord/123.
    val scheme = path.substringBefore("://", "")
    val rest = path.substringAfter("://", "")
    val host = rest.substringBefore('/')
    val segments = rest.substringAfter('/', "").split('/')
    if (scheme.isNotEmpty() && segments.size == 3 && segments[2].isNotEmpty() && segments[2].all { it.isDigit() }) {
        return "$scheme://$host/live/${segments[0]}/${segments[1]}/${segments[2]}.m3u8$query"
    }
    return url
}
