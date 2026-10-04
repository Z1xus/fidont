package us.z1x.fidont.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import us.z1x.fidont.R
import kotlin.math.abs

private const val PAGES = 3
private const val LAST = PAGES - 1

// the drawing uses the 512 unit grid of icon.svg
private const val GRID = 512f
private const val PHONE_CENTER = 190f
private const val KEY_SHIFT = 67f

private val DOT = 8.dp
private val DOT_GROWTH = 16.dp
private val DOT_GAP = 6.dp

@Composable
fun Onboarding(onDone: () -> Unit) {
    val setup = rememberSetup()
    OnboardingScreen(setup.secure, setup.provider, setup.onLock, setup.onProvider, onDone)
}

@Composable
fun OnboardingScreen(
    secure: Boolean,
    provider: Boolean,
    onLock: () -> Unit,
    onProvider: () -> Unit,
    onDone: () -> Unit,
    start: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(start) { PAGES }
    val ready = secure && provider
    val preview = LocalInspectionMode.current
    val entrance = remember { Animatable(if (preview) 1f else 0f) }
    // read in the draw phase, so a swipe does not recompose the screen on every frame
    val position = { pager.currentPage + pager.currentPageOffsetFraction }

    LaunchedEffect(Unit) {
        entrance.animateTo(1f, spring(stiffness = Spring.StiffnessLow))
    }
    BackHandler(pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Surface(color = scheme.surfaceContainer) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TextButton(
                onClick = onDone,
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 8.dp)
                        .graphicsLayer { alpha = (LAST - position()).coerceIn(0f, 1f) },
                enabled = pager.currentPage < LAST,
            ) { Text(stringResource(R.string.skip)) }
            Hero(position, { entrance.value }, Modifier.fillMaxWidth().weight(1f))
            HorizontalPager(pager, Modifier.fillMaxWidth().height(232.dp), verticalAlignment = Alignment.Top) { page ->
                Column(
                    Modifier.fillMaxSize().graphicsLayer {
                        val offset = position() - page
                        alpha = 1f - abs(offset).coerceIn(0f, 1f)
                        translationX = offset * size.width / 3
                    },
                ) {
                    when (page) {
                        0 -> {
                            Intro(R.string.onboarding_welcome, R.string.onboarding_welcome_body)
                        }

                        1 -> {
                            Intro(R.string.onboarding_safe, R.string.onboarding_safe_body)
                        }

                        else -> {
                            AnimatedContent(ready, Modifier.padding(start = 32.dp, end = 32.dp, bottom = 16.dp), label = "title") {
                                Text(
                                    stringResource(if (it) R.string.onboarding_ready else R.string.onboarding_setup),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            }
                            Step(R.drawable.ic_lock, R.string.lock_title, R.string.lock_body, secure, 0, 2, onLock)
                            Step(R.drawable.ic_shield, R.string.provider_title, R.string.provider_body, provider, 1, 2, onProvider)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Indicator(position)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (pager.currentPage < LAST) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else onDone()
                    },
                ) {
                    AnimatedContent(pager.currentPage == LAST, label = "action") {
                        Text(stringResource(if (it) R.string.start else R.string.next))
                    }
                }
            }
        }
    }
}

@Composable
private fun Indicator(position: () -> Float) {
    val scheme = MaterialTheme.colorScheme
    Canvas(Modifier.size(DOT * PAGES + DOT_GROWTH + DOT_GAP * LAST, DOT)) {
        var x = 0f
        repeat(PAGES) { page ->
            val active = 1f - abs(position() - page).coerceIn(0f, 1f)
            val width = (DOT + DOT_GROWTH * active).toPx()
            drawRoundRect(
                lerp(scheme.outlineVariant, scheme.primary, active),
                Offset(x, 0f),
                Size(width, size.height),
                CornerRadius(size.height),
            )
            x += width + DOT_GAP.toPx()
        }
    }
}

@Composable
private fun Intro(
    title: Int,
    body: Int,
) {
    Column(Modifier.padding(horizontal = 32.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(body),
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
fun Hero(
    position: () -> Float,
    entrance: () -> Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier) {
        val page = position()
        val blob = blend(page, scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer)
        val ink = blend(page, scheme.onPrimaryContainer, scheme.onTertiaryContainer, scheme.onSecondaryContainer)
        val unit = size.minDimension / GRID
        // the key slides out of the phone on the first page, a ring closes around it on the second
        val key = (1f - page).coerceIn(0f, 1f) * entrance()
        val guard = page.coerceIn(0f, 1f)
        val settled = (page - 1f).coerceIn(0f, 1f)
        val grown = 0.6f + 0.4f * entrance()
        val zoom = grown * (1f - 0.1f * settled)
        drawCircle(blob, 200f * unit * grown)
        withTransform({
            translate(size.width / 2, size.height / 2)
            scale(zoom * unit, zoom * unit, Offset.Zero)
            translate(-PHONE_CENTER - KEY_SHIFT * key, -GRID / 2)
        }) {
            phone(ink, blob, key)
            lock(ink.copy(alpha = guard))
            drawArc(
                color = scheme.primary,
                startAngle = -90f,
                sweepAngle = 360f * guard,
                useCenter = false,
                topLeft = Offset(PHONE_CENTER - 172f, GRID / 2 - 172f),
                size = Size(344f, 344f),
                style = Stroke(12f, cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.phone(
    ink: Color,
    screen: Color,
    key: Float,
) {
    drawRect(ink, Offset(250f, 236f), Size(8f + 134f * key, 40f))
    drawRect(ink, Offset(258f + 46f * key, 270f), Size(32f * key, 6f + 38f * key))
    drawRect(ink, Offset(258f + 100f * key, 270f), Size(34f * key, 6f + 58f * key))
    drawRoundRect(ink, Offset(122f, 138f), Size(136f, 236f), CornerRadius(34f))
    drawRoundRect(screen, Offset(150f, 166f), Size(80f, 180f), CornerRadius(12f))
    drawCircle(ink, 10f, Offset(PHONE_CENTER, 192f))
}

private fun DrawScope.lock(ink: Color) {
    drawArc(
        color = ink,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(PHONE_CENTER - 15f, 247f),
        size = Size(30f, 34f),
        style = Stroke(8f),
    )
    drawRoundRect(ink, Offset(PHONE_CENTER - 24f, 264f), Size(48f, 38f), CornerRadius(8f))
}

private fun blend(
    position: Float,
    first: Color,
    second: Color,
    third: Color,
): Color =
    if (position < 1f) {
        lerp(first, second, position.coerceAtLeast(0f))
    } else {
        lerp(second, third, (position - 1f).coerceAtMost(1f))
    }
