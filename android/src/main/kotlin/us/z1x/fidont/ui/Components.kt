package us.z1x.fidont.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val GroupCorner = 20.dp
private val InnerCorner = 4.dp
private val BadgeSize = 40.dp

@Composable
fun SectionHeader(
    text: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        stringResource(text),
        modifier.padding(start = 32.dp, top = 24.dp, end = 32.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
fun GroupItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val top = if (index == 0) GroupCorner else InnerCorner
    val bottom = if (index == count - 1) GroupCorner else InnerCorner
    Surface(
        onClick = onClick ?: {},
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp),
        enabled = onClick != null,
        shape = RoundedCornerShape(top, top, bottom, bottom),
        color = MaterialTheme.colorScheme.surfaceBright,
        content = content,
    )
}

@Composable
fun IconBadge(
    icon: Int,
    container: Color,
    size: Dp = BadgeSize,
) {
    Box(Modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(size * 0.55f), tint = contentColorFor(container))
    }
}

@Composable
fun Avatar(
    site: String,
    size: Dp = BadgeSize,
) {
    val scheme = MaterialTheme.colorScheme
    val containers = listOf(scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer)
    val container = containers[site.hashCode().mod(containers.size)]
    // the label before the top-level domain names the site
    val name = site.split('.').dropLast(1).lastOrNull() ?: site
    Box(Modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.take(1).uppercase(),
            color = contentColorFor(container),
            style = if (size > BadgeSize) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        )
    }
}
