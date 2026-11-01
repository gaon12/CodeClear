package xyz.gaon.codeclear.capture

/** Match only this component; short class names are valid in the secure setting. */
internal fun hasAccessibilityGrant(
    setting: String?,
    packageName: String,
    className: String
): Boolean {
    if (setting == null || setting.length > 32768) return false
    return setting.splitToSequence(':').take(128).any { entry ->
        val separator = entry.indexOf('/')
        if (separator <= 0 || separator != entry.lastIndexOf('/')) return@any false
        val owner = entry.substring(0, separator)
        val raw = entry.substring(separator + 1)
        val service = if (raw.startsWith('.')) owner + raw else raw
        owner == packageName && service == className
    }
}
