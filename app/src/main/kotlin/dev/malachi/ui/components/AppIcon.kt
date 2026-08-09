package dev.malachi.ui.components

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.malachi.data.AppInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// An icon is immutable per package, and the app list is scrolled a lot: caching in memory is
// what keeps the list from re-decoding the same drawable on every fling.
private val iconCache = LruCache<String, ImageBitmap>(256)

/** App icon decoded off the main thread, with a placeholder in its place until it arrives. */
@Composable
fun AppIcon(
    packageName: String,
    inventory: AppInventory,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    var bitmap by remember(packageName) { mutableStateOf(iconCache.get(packageName)) }
    val sizePx = with(LocalDensity.current) { size.roundToPx() }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            val decoded = withContext(Dispatchers.IO) {
                inventory.icon(packageName)?.toBitmap(sizePx, sizePx)?.asImageBitmap()
            }
            if (decoded != null) {
                iconCache.put(packageName, decoded)
                bitmap = decoded
            }
        }
    }

    val shape = RoundedCornerShape(size / 4)
    val current = bitmap
    if (current != null) {
        Image(bitmap = current, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
