package top.yukonga.mishka.ui.screen.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.CardSegment
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil

fun LazyListScope.latencySection(
    state: HomeUiState = HomeUiState(),
    onTestLatency: () -> Unit = {},
) {
    item(key = "latency_title") {
        LatencyHeader(state, onTestLatency)
    }
    // 两行各自独立 lazy item，CardSegment 分角拼回视觉连续的卡片。
    // 不走 groupedCardItems 辅助函数：平板 dashboard 会覆写 LocalHomeCardHorizontalPadding
    // 为非对称值（start=12/end=6 或反之），辅助函数只接受单一 Dp，段内自行承担 padding
    // 才能保留原来的水平留白语义。
    item(key = "latency:row1") {
        LatencySegment(isFirst = true, isLast = false, state = state)
    }
    item(key = "latency:row2") {
        LatencySegment(isFirst = false, isLast = true, state = state)
    }
}

@Composable
internal fun LatencySectionContent(
    state: HomeUiState = HomeUiState(),
    onTestLatency: () -> Unit = {},
) {
    LatencyHeader(state, onTestLatency)
    // 非 Lazy 上下文（平板 dashboard 的 Column），直接产出两段 CardSegment
    Column {
        LatencySegment(isFirst = true, isLast = false, state = state)
        LatencySegment(isFirst = false, isLast = true, state = state)
    }
}

/** 一行两个探针；四个探针排 2x2，避免窄屏四列把 Cloudflare 压到状态点上。 */
@Composable
private fun LatencySegment(isFirst: Boolean, isLast: Boolean, state: HomeUiState) {
    CardSegment(
        isFirst = isFirst,
        isLast = isLast,
        modifier = Modifier.padding(LocalHomeCardHorizontalPadding.current),
        outerHorizontalPadding = 0.dp,
        outerTopPadding = 0.dp,
        outerBottomPadding = if (isLast) 6.dp else 0.dp,
        insidePadding = PaddingValues(0.dp),
    ) {
        if (isFirst) {
            LatencyRow(
                "Xiaomi", state.latencyXiaomi,
                "Cloudflare", state.latencyCloudflare,
                16.dp, 6.dp,
            )
        } else {
            LatencyRow(
                "Google", state.latencyGoogle,
                "GitHub", state.latencyGithub,
                6.dp, 16.dp,
            )
        }
    }
}

@Composable
private fun ColumnScope.LatencyRow(
    firstName: String,
    firstDelay: Int,
    secondName: String,
    secondDelay: Int,
    topPadding: Dp,
    bottomPadding: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = bottomPadding),
    ) {
        LatencyItem(firstName, firstDelay)
        LatencyItem(secondName, secondDelay)
    }
}

@Composable
private fun LatencyHeader(
    state: HomeUiState,
    onTestLatency: () -> Unit,
) {
    val allTested = state.latencyXiaomi >= 0 || state.latencyCloudflare >= 0 ||
            state.latencyGoogle >= 0 || state.latencyGithub >= 0
    // Google 与 GitHub 同属「通常需代理才能通」的站点，任一通即说明代理出口可用；
    // 只有 Cloudflare 通仍算部分连通，与加入 GitHub 前的判定口径一致
    val proxiedReachable = state.latencyGoogle >= 0 || state.latencyGithub >= 0
    val statusColor = when {
        !allTested -> StatusColors.neutral
        proxiedReachable -> StatusColors.healthy
        state.latencyCloudflare >= 0 -> StatusColors.warning
        else -> StatusColors.danger
    }
    val statusText = when {
        !allTested -> stringResource(R.string.home_latency_untested)
        proxiedReachable -> stringResource(R.string.home_latency_normal)
        state.latencyCloudflare >= 0 -> stringResource(R.string.home_latency_partial)
        else -> stringResource(R.string.home_latency_abnormal)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallTitle(text = stringResource(R.string.home_latency))
        if (state.isRunning) {
            Row(
                modifier = Modifier.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!state.latencyViaRules) {
                    Text(
                        text = stringResource(R.string.home_latency_not_via_rules),
                        modifier = Modifier
                            .squircleBackground(StatusColors.warning.copy(alpha = 0.14f), 6.dp)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = StatusColors.warning,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = statusColor,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                val rotation = remember { Animatable(0f) }
                LaunchedEffect(state.isTestingLatency) {
                    if (state.isTestingLatency) {
                        while (true) {
                            rotation.animateTo(
                                targetValue = rotation.value + 360f,
                                animationSpec = tween(durationMillis = 800, easing = LinearEasing),
                            )
                        }
                    } else if (rotation.value % 360f != 0f) {
                        val target = ceil(rotation.value / 360f) * 360f
                        rotation.animateTo(
                            targetValue = target,
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        )
                        rotation.snapTo(0f)
                    }
                }
                IconButton(
                    onClick = { if (!state.isTestingLatency) onTestLatency() },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Refresh,
                        contentDescription = stringResource(R.string.common_refresh),
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = rotation.value },
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.LatencyItem(name: String, delay: Int) {
    // -1 视为「未测/不可达」，由 token 映射到 neutral 灰
    val color = StatusColors.delay(if (delay < 0) null else delay)

    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = FormatUtils.formatLatency(delay),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}
