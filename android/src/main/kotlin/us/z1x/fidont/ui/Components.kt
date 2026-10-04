package us.z1x.fidont.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import us.z1x.fidont.R

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

@Composable
fun Entry(
    index: Int,
    count: Int,
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    GroupItem(index, count, modifier, onClick) {
        ListItem(
            headlineContent = { Text(headline) },
            supportingContent = supporting?.let { { Text(it) } },
            leadingContent = leading,
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
fun Step(
    icon: Int,
    title: Int,
    body: Int,
    done: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Entry(
        index = index,
        count = count,
        headline = stringResource(title),
        modifier = modifier,
        supporting = stringResource(body),
        leading = { IconBadge(icon, if (done) scheme.primaryContainer else scheme.errorContainer) },
        onClick = onClick.takeUnless { done },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: Int,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surfaceContainer),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            content = content,
        )
    }
}
