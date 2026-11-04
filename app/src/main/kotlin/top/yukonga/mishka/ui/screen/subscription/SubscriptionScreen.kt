package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.domain.model.Subscription
import top.yukonga.mishka.domain.sharelink.ShareLinkParser
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.util.formatEpochMillisAsLocal
import top.yukonga.mishka.viewmodel.ProfileOperation
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 订阅列表页面
 */
@Composable
fun SubscriptionScreen(
    viewModel: SubscriptionViewModel,
    bottomPadding: Dp = 0.dp,
    onNavigateAdd: () -> Unit = {},
    onNavigateEdit: (uuid: String) -> Unit = {},
    onActiveChanged: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.subscription_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {},
                    actions = {
                        // 与 updateAllSubscriptions 的筛选条件保持一致：单节点的 source 非空但
                        // 不是订阅地址，用它判空会让「全部更新」可见却点了没反应
                        if (uiState.subscriptions.any { it.type == ProfileType.Url && it.url.isNotBlank() }) {
                            IconButton(
                                onClick = { viewModel.updateAllSubscriptions() },
                                enabled = !uiState.isLoading,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    contentDescription = stringResource(R.string.subscription_update_all),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.clearError(); onNavigateAdd() }) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = stringResource(R.string.subscription_add),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomPadding,
                    start = sidePadding,
                    end = sidePadding,
                ),
            ) {
                if (uiState.subscriptions.isNotEmpty()) {
                    item(key = "top_padding") {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                if (uiState.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = uiState.error,
                                color = StatusColors.danger,
                            )
                        }
                    }
                }

                if (uiState.subscriptions.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier.fillParentMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.subscription_no_config),
                                fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = stringResource(R.string.subscription_tap_add),
                                modifier = Modifier.padding(top = 6.dp),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                items(uiState.subscriptions, key = { it.id }) { sub ->
                    SubscriptionItem(
                        subscription = sub,
                        isLoading = uiState.isLoading,
                        onSelect = {
                            viewModel.setActive(sub.id)
                            onActiveChanged?.invoke()
                        },
                        onRefresh = { viewModel.fetchSubscription(sub.id) },
                        onDelete = {
                            viewModel.removeSubscription(sub.id) { onActiveChanged?.invoke() }
                        },
                        onEdit = { onNavigateEdit(sub.id) },
                    )
                }
            }
        }
    }

    // 批量更新优先展示（含 currentName + (done/total) + step）；
    // 单条走 importProgress，标题由 operation 区分（列表页触发的是 Update）。
    val updateAll = uiState.updateAll
    if (updateAll != null) {
        val progressText = stringResource(
            R.string.subscription_updating_progress,
            updateAll.currentName,
            updateAll.completed + 1,
            updateAll.total,
        )
        val step = updateAll.currentStep?.let { "$progressText\n${importStepLabel(it)}" } ?: progressText
        ImportProgressDialog(
            show = true,
            step = step,
            title = stringResource(R.string.subscription_updating_title),
            onCancel = { viewModel.cancelCurrentUpdate() },
        )
    } else {
        val title = when (uiState.operation) {
            ProfileOperation.Update -> stringResource(R.string.subscription_update_config)
            else -> stringResource(R.string.subscription_import_config)
        }
        ImportProgressDialog(
            show = uiState.importProgress != null,
            step = uiState.importProgress?.let { importStepLabel(it) } ?: stringResource(R.string.common_processing),
            title = title,
            onCancel = { viewModel.cancelCurrentUpdate() },
        )
    }
}

@Composable
private fun SubscriptionItem(
    subscription: Subscription,
    isLoading: Boolean,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val displayName = subscription.name.ifBlank { stringResource(R.string.subscription_config) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
        onClick = onSelect,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = subscription.name.ifBlank { stringResource(R.string.subscription_config) },
                modifier = Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight(550),
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (subscription.isActive) {
                val activeColor = StatusColors.healthy
                Text(
                    text = stringResource(R.string.subscription_in_use),
                    fontSize = 12.sp,
                    fontWeight = FontWeight(750),
                    color = activeColor,
                    modifier = Modifier
                        .squircleBackground(activeColor.copy(alpha = 0.15f), 6.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row {

            Column(
                modifier = Modifier.wrapContentSize()
            ) {
                if (subscription.total > 0) {
                    val used = subscription.upload + subscription.download
                    Text(
                        text = stringResource(
                            R.string.subscription_used_traffic,
                            FormatUtils.formatBytes(used),
                            FormatUtils.formatBytes(subscription.total)
                        ),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.subscription_no_traffic),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                Spacer(Modifier.weight(1f))

                if (subscription.updatedAt > 0) {
                    Text(
                        text = stringResource(R.string.subscription_updated_at, formatEpochMillisAsLocal(subscription.updatedAt)),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = onEdit,
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.MoreCircle,
                    contentDescription = stringResource(R.string.common_edit),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            // 单节点链接是静态配置，没有订阅可拉取：直接隐藏更新按钮，而不是留一个灰的
            if (!ShareLinkParser.isShareLink(subscription.url)) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading && subscription.url.isNotBlank(),
                    minHeight = 35.dp,
                    minWidth = 35.dp,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        modifier = Modifier.size(20.dp),
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.common_update),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { showDeleteDialog = true },
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    WindowDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.subscription_delete_title),
        summary = stringResource(R.string.subscription_delete_summary, displayName),
        onDismissRequest = { showDeleteDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = { showDeleteDialog = false },
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showDeleteDialog = false
                    onDelete()
                },
            )
        }
    }
}
