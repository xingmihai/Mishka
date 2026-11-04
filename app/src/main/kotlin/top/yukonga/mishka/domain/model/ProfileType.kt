package top.yukonga.mishka.domain.model

import kotlinx.serialization.Serializable

/**
 * 订阅类型枚举。Room 中通过 ProfileTypeConverter 转为 TEXT 列存储。
 *
 * 单节点分享链接复用 [File]：不联网拉取，但 `source` 存原始链接（普通配置文件为空串）。
 * 用 `ShareLinkParser.isShareLink(source)` 区分二者。不为此新增枚举值，是为了让任何能
 * 读取本数据库的构建都把这类记录看成静态配置，而不是订阅地址。
 */
@Serializable
enum class ProfileType {
    File, Url, External;

    companion object {
        /**
         * 未知持久化值一律降级为 [File]：静态配置不联网、不参与刷新，是安全的兜底。
         * 降级成 [Url] 会让非 http 的 source 被交给 fetch 管线当订阅地址下载。
         */
        fun fromStringOrDefault(value: String?, default: ProfileType = File): ProfileType =
            runCatching { value?.let { valueOf(it) } }.getOrNull() ?: default
    }
}
