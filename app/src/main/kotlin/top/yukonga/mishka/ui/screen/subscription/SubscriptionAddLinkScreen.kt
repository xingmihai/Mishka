package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.sharelink.ShareLinkParser
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.HintCard
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 单节点分享链接导入页面。
 *
 * 一次一个节点：解析成 mihomo 配置后按 File 类型走导入管线（不需要联网 fetch）。
 * 多节点请先合并进订阅，本页不承载节点管理职责。
 *
 * 骨架与 [SubscriptionAddUrlScreen] 同源：Glass 顶栏、PageBackdropBox 给
 * [ImportProgressDialog] 提供采样宿主、layerBackdrop 先于 horizontalCutoutPadding，
 * 输入框统一用 TextFieldState（Compose 1.13 约定）。
 */
@Composable
fun SubscriptionAddLinkScreen(
    viewModel: SubscriptionViewModel,
    initialLink: String = "",
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val nameState = rememberTextFieldState()
    val linkState = rememberTextFieldState(initialText = initialLink)

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.subscription_config),
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { BackNavigationIcon(onBack) },
                    )
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .horizontalCutoutPadding()
                    .scrollEndHaptic()
                    .overScrollVertical(scrollBehavior)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                ),
            ) {
                item(key = "hint") {
                    HintCard(
                        text = stringResource(
                            R.string.subscription_link_hint,
                            ShareLinkParser.supportedSchemeList(),
                        ),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item(key = "name_title") {
                    SmallTitle(text = stringResource(R.string.subscription_name))
                }
                item(key = "name_field") {
                    TextField(
                        state = nameState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        label = stringResource(R.string.subscription_link_name_placeholder),
                        useLabelAsPlaceholder = true,
                    )
                }
                item(key = "link_title") {
                    SmallTitle(text = stringResource(R.string.subscription_link_label))
                }
                item(key = "link_field") {
                    // 与项目内其它 TextField 保持同一参数集：miuix 的 TextField 签名不受本仓库控制，
                    // 不引入未经验证的 maxLines / 固定高度参数
                    TextField(
                        state = linkState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                        label = stringResource(R.string.subscription_link_placeholder),
                        useLabelAsPlaceholder = true,
                    )
                }
                item(key = "save") {
                    TextButton(
                        text = stringResource(R.string.common_save),
                        onClick = {
                            viewModel.addFromLink(
                                name = nameState.text.toString().trim(),
                                link = linkState.text.toString().trim(),
                                onComplete = onSaved,
                            )
                        },
                        enabled = linkState.text.isNotBlank() && !uiState.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                if (uiState.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = uiState.error,
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                item(key = "bottom_spacer") {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }

        ImportProgressDialog(
            show = uiState.isLoading,
            step = uiState.importProgress?.let { importStepLabel(it) } ?: stringResource(R.string.common_processing),
            onCancel = { viewModel.cancelCurrentUpdate() },
        )
    }
}
