package top.yukonga.mishka.domain.sharelink

import java.util.Base64

/**
 * 单节点分享链接 → mihomo proxy 对象。
 *
 * 输出的是 mihomo 的 `proxies[]` 条目（字段顺序即插入顺序），由 [toConfig] 包成最小可运行配置。
 * 生成的配置仍走 ProfileProcessor → 由 mihomo 自己校验，字段映射有误会在导入时报错而非静默出错。
 *
 * 本类不依赖 android / compose / ktor / room，纯 JDK + Kotlin。
 */
class ShareLinkException(message: String) : IllegalArgumentException(message)

/** 解析出的单个节点。`fields` 用 LinkedHashMap 保证 YAML 输出顺序稳定、可读。 */
data class ParsedProxy(
    val name: String,
    val fields: LinkedHashMap<String, Any?>,
) {
    val type: String get() = fields["type"] as? String ?: ""
}

object ShareLinkParser {

    /** 支持的协议前缀。扫码自动识别只认这批，其余按 URL 订阅处理。 */
    private val SUPPORTED_SCHEMES = setOf(
        "vless", "vmess", "ss", "shadowsocks",
        "trojan", "hysteria2", "hy2", "tuic", "anytls",
    )

    fun isShareLink(link: String): Boolean {
        val scheme = link.substringBefore("://", "").trim().lowercase()
        return scheme in SUPPORTED_SCHEMES
    }

    fun supportedSchemeList(): String = "vless / vmess / ss / trojan / hysteria2 / tuic / anytls"

    fun parse(raw: String): ParsedProxy {
        val link = raw.trim()
        if (link.isEmpty()) throw ShareLinkException("empty link")
        val scheme = link.substringBefore("://", "").lowercase()
        if (scheme !in SUPPORTED_SCHEMES) {
            throw ShareLinkException("unsupported scheme: ${scheme.ifEmpty { "<none>" }}")
        }
        return when (scheme) {
            "vless" -> parseVless(link)
            "vmess" -> parseVmess(link)
            "ss", "shadowsocks" -> parseShadowsocks(link)
            "trojan" -> parseTrojan(link)
            "hysteria2", "hy2" -> parseHysteria2(link)
            "tuic" -> parseTuic(link)
            else -> parseAnyTls(link)
        }
    }

    /**
     * 改名。**必须同时改 [ParsedProxy.name] 与 `fields["name"]`**：toConfig 里 proxy 条目的
     * name 取自 fields，而 proxy-groups 的引用取自 name，两处不一致会让 select 组指向不存在
     * 的节点。data class 的 copy 不会联动 fields，所以不能直接用它改名。
     */
    fun renamed(proxy: ParsedProxy, name: String): ParsedProxy {
        val fields = LinkedHashMap(proxy.fields)
        fields["name"] = name
        return ParsedProxy(name, fields)
    }

    /**
     * 把节点包成一份可直接启动的最小配置：单 proxy + 一个 select 组 + MATCH 兜底。
     * 单节点没有规则可言，不写 rules 会让 mihomo 拿不到任何出站，必须有这一条。
     */
    fun toConfig(proxy: ParsedProxy): String = buildString {
        // 节点名与组名不能重名：链接备注就叫 "PROXY" 时，select 组与 proxy 同名会让
        // mihomo 无法解析 MATCH 的引用而拒绝整份配置。组名加后缀直到互不冲突
        var group = "PROXY"
        while (group == proxy.name) group += "_"
        append("proxies:\n")
        appendProxyEntry(proxy.fields, this)
        append('\n')
        append("proxy-groups:\n")
        append("  - name: ").append(scalar(group)).append('\n')
        append("    type: select\n")
        append("    proxies:\n")
        append("      - ").append(scalar(proxy.name)).append('\n')
        append('\n')
        append("rules:\n")
        append("  - MATCH,").append(scalar(group)).append('\n')
    }

    // ========== VLESS ==========

    private fun parseVless(link: String): ParsedProxy {
        val name = fragmentOf(link)
        val body = link.substringAfter("://").substringBefore("#")
        val query = queryOf(body)
        // userinfo 之后可能还有 ?query，host:port 在 @ 与 ? 之间
        val authority = body.substringAfter("@", "").substringBefore("?")
        if (authority.isEmpty()) throw ShareLinkException("vless: missing server address")
        val uuid = body.substringBefore("@", "").percentDecode()
        if (uuid.isEmpty()) throw ShareLinkException("vless: missing uuid")
        val (host, port) = splitHostPort(authority)

        val f = LinkedHashMap<String, Any?>()
        f["name"] = name.ifEmpty { host }
        f["type"] = "vless"
        f["server"] = host
        f["port"] = port
        f["uuid"] = uuid
        f["udp"] = true

        val security = query["security"]?.lowercase() ?: "none"
        val network = query["type"]?.lowercase() ?: "tcp"
        if (network != "tcp") f["network"] = network
        val sni = query["sni"]
        val hostParam = query["host"]
        if (!sni.isNullOrEmpty()) f["servername"] = sni
        else if (!hostParam.isNullOrEmpty()) f["servername"] = hostParam

        if (security == "tls" || security == "reality") f["tls"] = true
        if (security == "reality") {
            val reality = LinkedHashMap<String, Any?>()
            val pbk = query["pbk"]
            if (!pbk.isNullOrEmpty()) reality["public-key"] = pbk
            if (!query["sid"].isNullOrEmpty()) reality["short-id"] = query["sid"]
            if (reality.isNotEmpty()) f["reality-opts"] = reality
            else throw ShareLinkException("vless: reality link without public-key")
        }
        if (!query["flow"].isNullOrEmpty()) f["flow"] = query["flow"]
        if (!query["fp"].isNullOrEmpty()) f["client-fingerprint"] = query["fp"]
        if (!query["packetEncoding"].isNullOrEmpty()) f["packet-encoding"] = query["packetEncoding"]
        if (query["allowInsecure"] == "1" || query["allowInsecure"] == "true") f["skip-cert-verify"] = true
        alpnList(query["alpn"])?.let { f["alpn"] = it }
        transportOpts(network, query)?.let { f.putAll(it) }
        return ParsedProxy(f["name"] as String, f)
    }

    // ========== VMESS ==========

    private fun parseVmess(link: String): ParsedProxy {
        val payload = link.substringAfter("://").substringBefore("#")
        val json = runCatching { String(decodeBase64(payload)) }
            .getOrElse { throw ShareLinkException("vmess: payload is not valid base64") }
        val map = parseJsonObject(json)
        val host = map["add"]?.takeIf { it.isNotEmpty() }
            ?: throw ShareLinkException("vmess: missing server address")
        val port = map["port"]?.toIntOrNull()
            ?: throw ShareLinkException("vmess: invalid port")
        val uuid = map["id"]?.takeIf { it.isNotEmpty() }
            ?: throw ShareLinkException("vmess: missing uuid")

        val f = LinkedHashMap<String, Any?>()
        f["name"] = map["ps"]?.takeIf { it.isNotEmpty() } ?: host
        f["type"] = "vmess"
        f["server"] = host
        f["port"] = port
        f["uuid"] = uuid
        f["alterId"] = map["aid"]?.toIntOrNull() ?: 0
        f["cipher"] = map["scy"]?.takeIf { it.isNotEmpty() } ?: "auto"
        f["udp"] = true

        val net = map["net"]?.lowercase() ?: "tcp"
        if (net != "tcp") f["network"] = net
        val tlsRaw = map["tls"]
        val tls = tlsRaw == "tls" || tlsRaw == "true"
        if (tls) {
            f["tls"] = true
            val sni = map["sni"]?.takeIf { it.isNotEmpty() } ?: map["host"]?.takeIf { it.isNotEmpty() }
            if (!sni.isNullOrEmpty()) f["servername"] = sni
            // vmess 的 allowInsecure 独立于 tls 字段；不映射会在自签证书上直接握手失败
            if (map["allowInsecure"] == "true" || map["allowInsecure"] == "1") {
                f["skip-cert-verify"] = true
            }
        }
        if (!map["sni"].isNullOrEmpty() && !tls) f["servername"] = map["sni"]
        if (!map["fp"].isNullOrEmpty()) f["client-fingerprint"] = map["fp"]
        alpnList(map["alpn"])?.let { f["alpn"] = it }
        when (net) {
            // ws 复用公共 wsOpts：vmess 的 path 同样可能带 ?ed= 早期数据
            "ws" -> {
                val opts = wsOpts(map["path"], map["host"])
                if (opts.isNotEmpty()) f["ws-opts"] = opts
            }

            "h2" -> {
                val opts = LinkedHashMap<String, Any?>()
                map["path"]?.takeIf { it.isNotEmpty() }?.let { opts["path"] = it }
                map["host"]?.takeIf { it.isNotEmpty() }?.let { opts["host"] = splitAndTrim(it) }
                if (opts.isNotEmpty()) f["h2-opts"] = opts
            }

            "grpc" -> {
                val serviceName = map["path"]?.takeIf { it.isNotEmpty() }
                if (serviceName != null) f["grpc-opts"] = mapOf("grpc-service-name" to serviceName)
            }

            "http" -> {
                val opts = LinkedHashMap<String, Any?>()
                // 与 vless/trojan 一致：http-opts.path 是列表
                map["path"]?.takeIf { it.isNotEmpty() }?.let { opts["path"] = listOf(it) }
                map["host"]?.takeIf { it.isNotEmpty() }?.let { opts["headers"] = mapOf("Host" to listOf(it)) }
                if (opts.isNotEmpty()) f["http-opts"] = opts
            }
        }
        return ParsedProxy(f["name"] as String, f)
    }

    // ========== SHADOWSOCKS ==========

    private fun parseShadowsocks(link: String): ParsedProxy {
        val name = fragmentOf(link)
        val body = link.substringAfter("://").substringBefore("#")
        val query = queryOf(body)
        val main = body.substringBefore("?")

        val (userinfo, authority) = if (main.contains("@")) {
            main.substringBeforeLast("@") to main.substringAfterLast("@")
        } else {
            // 整段 base64("method:password@host:port") 的旧格式
            val decoded = runCatching { String(decodeBase64(main)) }
                .getOrElse { throw ShareLinkException("ss: payload is not valid base64") }
            decoded.substringBeforeLast("@") to decoded.substringAfterLast("@")
        }
        if (authority.isEmpty()) throw ShareLinkException("ss: missing server address")

        val method: String
        val password: String
        if (userinfo.contains(":")) {
            method = userinfo.substringBefore(":")
            password = userinfo.substringAfter(":")
        } else {
            // SIP002：userinfo 为 base64url("method:password")
            val decoded = runCatching { String(decodeBase64(userinfo)) }
                .getOrElse { throw ShareLinkException("ss: userinfo is not valid base64") }
            if (!decoded.contains(":")) throw ShareLinkException("ss: missing password")
            method = decoded.substringBefore(":")
            password = decoded.substringAfter(":")
        }
        if (method.isEmpty()) throw ShareLinkException("ss: missing cipher")
        if (password.isEmpty()) throw ShareLinkException("ss: missing password")
        val (host, port) = splitHostPort(authority)

        val f = LinkedHashMap<String, Any?>()
        f["name"] = name.ifEmpty { host }
        f["type"] = "shadowsocks"
        f["server"] = host
        f["port"] = port
        f["cipher"] = method
        f["password"] = password
        f["udp"] = true
        // ?plugin=obfs-local;obfs=http;obfs-host=xx
        val plugin = query["plugin"]
        if (!plugin.isNullOrEmpty()) {
            val parts = plugin.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val pluginName = parts.firstOrNull() ?: ""
            val normalized = when {
                pluginName.startsWith("obfs") -> "obfs"
                pluginName.startsWith("v2ray") -> "v2ray-plugin"
                pluginName.startsWith("shadow-tls") -> "shadow-tls"
                pluginName.startsWith("restls") -> "restls"
                else -> pluginName
            }
            if (normalized.isEmpty()) throw ShareLinkException("ss: empty plugin")
            f["plugin"] = normalized
            val opts = LinkedHashMap<String, Any?>()
            for (part in parts.drop(1)) {
                val separator = part.indexOf('=')
                // 裸开关（如 v2ray-plugin 的 ";tls"）没有 =，等价于开启该选项，不能跳过
                val k = if (separator >= 0) part.substring(0, separator) else part
                val v = if (separator >= 0) part.substring(separator + 1) else "true"
                if (k.isEmpty()) continue
                opts[when (k) {
                    "obfs" -> "mode"
                    "obfs-host" -> "host"
                    "obfs-uri" -> "path"
                    else -> k
                }] = v
            }
            if (opts.isNotEmpty()) f["plugin-opts"] = opts
        }
        return ParsedProxy(f["name"] as String, f)
    }

    // ========== TROJAN ==========

    private fun parseTrojan(link: String): ParsedProxy {
        val name = fragmentOf(link)
        val body = link.substringAfter("://").substringBefore("#")
        val query = queryOf(body)
        val authority = body.substringAfter("@", "").substringBefore("?")
        val password = body.substringBefore("@", "").percentDecode()
        if (password.isEmpty()) throw ShareLinkException("trojan: missing password")
        if (authority.isEmpty()) throw ShareLinkException("trojan: missing server address")
        val (host, port) = splitHostPort(authority)

        val f = LinkedHashMap<String, Any?>()
        f["name"] = name.ifEmpty { host }
        f["type"] = "trojan"
        f["server"] = host
        f["port"] = port
        f["password"] = password
        f["udp"] = true
        val sni = query["sni"]?.takeIf { it.isNotEmpty() } ?: query["peer"]?.takeIf { it.isNotEmpty() }
        if (!sni.isNullOrEmpty()) f["sni"] = sni
        alpnList(query["alpn"])?.let { f["alpn"] = it }
        if (query["allowInsecure"] == "1" || query["allowInsecure"] == "true") f["skip-cert-verify"] = true
        if (!query["fp"].isNullOrEmpty()) f["client-fingerprint"] = query["fp"]
        val network = query["type"]?.lowercase() ?: "tcp"
        if (network != "tcp") f["network"] = network
        transportOpts(network, query)?.let { f.putAll(it) }
        return ParsedProxy(f["name"] as String, f)
    }

    // ========== HYSTERIA2 ==========

    private fun parseHysteria2(link: String): ParsedProxy {
        val name = fragmentOf(link)
        val body = link.substringAfter("://").substringBefore("#")
        val query = queryOf(body)
        val authority = body.substringAfter("@", "").substringBefore("?")
        val password = body.substringBefore("@", "").percentDecode()
        if (password.isEmpty()) throw ShareLinkException("hysteria2: missing password")
        if (authority.isEmpty()) throw ShareLinkException("hysteria2: missing server address")
        val (host, port) = splitHostPort(authority)

        val f = LinkedHashMap<String, Any?>()
        f["name"] = name.ifEmpty { host }
        f["type"] = "hysteria2"
        f["server"] = host
        f["port"] = port
        f["password"] = password
        if (!query["sni"].isNullOrEmpty()) f["sni"] = query["sni"]
        if (!query["obfs"].isNullOrEmpty()) f["obfs"] = query["obfs"]
        if (!query["obfs-password"].isNullOrEmpty()) f["obfs-password"] = query["obfs-password"]
        alpnList(query["alpn"])?.let { f["alpn"] = it }
        if (query["insecure"] == "1" || query["insecure"] == "true") f["skip-cert-verify"] = true
        if (!query["up"].isNullOrEmpty()) f["up"] = query["up"]
        if (!query["down"].isNullOrEmpty()) f["down"] = query["down"]
        if (!query["ports"].isNullOrEmpty()) f["ports"] = query["ports"]
        return ParsedProxy(f["name"] as String, f)
    }

    // ========== TUIC ==========

    private fun parseTuic(link: String): ParsedProxy {
        val name = fragmentOf(link)
        val body = link.substringAfter("://").substringBefore("#")
        val query = queryOf(body)
        val authority = body.substringAfter("@", "").substringBefore("?")
        val userinfo = body.substringBefore("@", "").percentDecode()
        if (authority.isEmpty()) throw ShareLinkException("tuic: missing server address")
        val uuid = userinfo.substringBefore(":", "")
        val password = userinfo.substringAfter(":", "")
        if (uuid.isEmpty()) throw ShareLinkException("tuic: missing uuid")
        if (password.isEmpty()) throw ShareLinkException("tuic: missing password")
        val (host, port) = splitHostPort(authority)

        val f = LinkedHashMap<String, Any?>()
        f["name"] = name.ifEmpty { host }
        f["type"] = "tuic"
        f["server"] = host
        f["port"] = port
        f["uuid"] = uuid
        f["password"] = password
        if (!query["congestion_control"].isNullOrEmpty()) {
            f["congestion-controller"] = query["congestion_control"]
        }
        if (!query["udp_relay_mode"].isNullOrEmpty()) f["udp-relay-mode"] = query["udp_relay_mode"]
        alpnList(query["alpn"])?.let { f["alpn"] = it }
        if (!query["sni"].isNullOrEmpty()) f["sni"] = query["sni"]
        if (query["allow_insecure"] == "1" || query["allow_insecure"] == "true") f["skip-cert-verify"] = true
        if (query["disable_sni"] == "1" || query["disable_sni"] == "true") f["disable-sni"] = true
        if (query["reduce_rtt"] == "1" || query["reduce_rtt"] == "true") f["reduce-rtt"] = true
        return ParsedProxy(f["name"] as String, f)
    }

    // ========== ANYTLS ==========

    private fun parseAnyTls(link: String): ParsedProxy {
        val name = fragmentOf(link)
        val body = link.substringAfter("://").substringBefore("#")
        val query = queryOf(body)
        val authority = body.substringAfter("@", "").substringBefore("?")
        val password = body.substringBefore("@", "").percentDecode()
        if (password.isEmpty()) throw ShareLinkException("anytls: missing password")
        if (authority.isEmpty()) throw ShareLinkException("anytls: missing server address")
        val (host, port) = splitHostPort(authority)

        val f = LinkedHashMap<String, Any?>()
        f["name"] = name.ifEmpty { host }
        f["type"] = "anytls"
        f["server"] = host
        f["port"] = port
        f["password"] = password
        f["udp"] = true
        if (!query["sni"].isNullOrEmpty()) f["sni"] = query["sni"]
        alpnList(query["alpn"])?.let { f["alpn"] = it }
        if (query["insecure"] == "1" || query["insecure"] == "true") f["skip-cert-verify"] = true
        if (!query["fp"].isNullOrEmpty()) f["client-fingerprint"] = query["fp"]
        return ParsedProxy(f["name"] as String, f)
    }

    // ========== 公共传输层参数 ==========

    /**
     * ws / grpc / h2 / http 的传输参数。vless、trojan 共用同一套 query 语义（v2rayN 风格）。
     * ws 的 `?ed=` 早期数据只有 vless 会用，这里统一处理。
     */
    private fun transportOpts(network: String, query: Map<String, String>): LinkedHashMap<String, Any?>? {
        val path = query["path"]
        val host = query["host"]
        val serviceName = query["serviceName"]
        return when (network) {
            "ws" -> {
                val opts = wsOpts(path, host)
                if (opts.isEmpty()) null else LinkedHashMap<String, Any?>().apply { put("ws-opts", opts) }
            }

            "grpc" -> if (serviceName.isNullOrEmpty()) null else {
                LinkedHashMap<String, Any?>().apply {
                    put("grpc-opts", mapOf("grpc-service-name" to serviceName))
                }
            }

            "h2" -> {
                val opts = LinkedHashMap<String, Any?>()
                path?.takeIf { it.isNotEmpty() }?.let { opts["path"] = it }
                host?.takeIf { it.isNotEmpty() }?.let { opts["host"] = splitAndTrim(it) }
                if (opts.isEmpty()) null else LinkedHashMap<String, Any?>().apply { put("h2-opts", opts) }
            }

            "http" -> {
                val opts = LinkedHashMap<String, Any?>()
                // mihomo 的 http-opts.path 是字符串列表，写成单字符串会校验失败
                path?.takeIf { it.isNotEmpty() }?.let { opts["path"] = listOf(it) }
                host?.takeIf { it.isNotEmpty() }?.let { opts["headers"] = mapOf("Host" to listOf(it)) }
                if (opts.isEmpty()) null else LinkedHashMap<String, Any?>().apply { put("http-opts", opts) }
            }

            else -> null
        }
    }

    /**
     * ws 传输参数，vless/trojan 与 vmess 共用。
     *
     * path 里的 `?ed=2048` 是早期数据长度（v2rayN / 多数订阅生成器都这么写），必须剥出来
     * 写成 mihomo 的 max-early-data，否则 path 带着 `?ed=` 一起发出去，握手直接失败。
     */
    private fun wsOpts(path: String?, host: String?): LinkedHashMap<String, Any?> {
        val opts = LinkedHashMap<String, Any?>()
        val cleanPath = path?.substringBefore("?")
        cleanPath?.takeIf { it.isNotEmpty() }?.let { opts["path"] = it }
        host?.takeIf { it.isNotEmpty() }?.let { opts["headers"] = mapOf("Host" to it) }
        val ed = path?.substringAfter("?ed=", "")?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (ed != null) {
            opts["max-early-data"] = ed
            opts["early-data-header-name"] = "Sec-WebSocket-Protocol"
        }
        return opts
    }

    private fun alpnList(value: String?): List<String>? {
        val list = splitAndTrim(value ?: return null)
        return list.ifEmpty { null }
    }

    // ========== 工具 ==========

    private fun splitHostPort(authority: String): Pair<String, Int> {
        // 形如 host:443/path 的链接（部分生成器会带 path，ws 的 path 也可能写在这里），
        // 路径不属于 authority，混进来会让端口解析成 "443/" 而整个导入失败
        val authorityWithoutPath = authority.substringBefore("/")
        val cleaned = authorityWithoutPath.substringAfter("[").let {
            // IPv6 字面量：[::1]:443
            if (authorityWithoutPath.startsWith("[")) it.substringBefore("]") else authorityWithoutPath
        }
        val host = if (authority.startsWith("[")) cleaned else authority.substringBeforeLast(":")
        val portRaw = authority.substringAfterLast(":")
        val port = portRaw.toIntOrNull()
            ?: throw ShareLinkException("invalid port: $portRaw")
        if (host.isEmpty()) throw ShareLinkException("missing server host")
        return host to port
    }

    private fun fragmentOf(link: String): String =
        link.substringAfter("#", "").percentDecode().trim()

    private fun queryOf(body: String): Map<String, String> {
        val raw = body.substringAfter("?", "").takeIf { it.isNotEmpty() } ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        raw.split("&").forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val k = pair.substringBefore("=")
            val v = pair.substringAfter("=", "")
            if (k.isEmpty()) return@forEach
            map[k.percentDecode()] = v.percentDecode()
        }
        return map
    }

    private fun splitAndTrim(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 百分号解码。**不能用 URLDecoder.decode**：那是 form 风格，会把字面 `+` 解成空格，
     * 而分享链接里的凭据（trojan/hy2/anytls 的 password、vless 的 uuid）常含 `+`，
     * 一解码就坏、节点连不上。这里只做 %XX，字面 `+` 原样保留。
     *
     * 非法转义（如裸 `%`）原样保留而不是报错：导入器对边角链接宽容一点，让用户能先
     * 导入再由 mihomo 校验，比直接拒绝更好定位问题。
     */
    private fun String.percentDecode(): String {
        if (indexOf('%') < 0) return this
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            // 需要 i+1、i+2 两个字符都在界内
            if (c == '%' && i + 2 < length) {
                val hex = substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    out.append(value.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun decodeBase64(value: String): ByteArray {
        var text = value.trim().replace('-', '+').replace('_', '/')
        while (text.length % 4 != 0) text += "="
        return Base64.getDecoder().decode(text)
    }

    /**
     * JSON 字符串反转义。逐字符处理而不是链式 replace：链式 replace 会把 `\\n` 之类
     * 二次转义的顺序弄错，也会漏掉 `\uXXXX`（备注里带非 ASCII 时很常见），留下字面
     * 的反斜杠-u 文本让节点备注变成乱码。
     */
    private fun unescapeJson(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                sb.append(c)
                i++
                continue
            }
            when (val esc = value[i + 1]) {
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'b' -> { sb.append('\u0008'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    val hex = value.substring(i + 2, (i + 6).coerceAtMost(value.length))
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 4 && code != null) {
                        sb.append(code.toChar())
                        i += 6
                    } else {
                        // 非法 \u 转义：保留原样，交给 mihomo 校验时暴露
                        sb.append(c)
                        i++
                    }
                }

                else -> { sb.append(esc); i += 2 }
            }
        }
        return sb.toString()
    }

    /** vmess 的 base64 里是一段 JSON；这里只做够用的解析，不引序列化框架。 */
    private fun parseJsonObject(json: String): Map<String, String> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw ShareLinkException("vmess: payload is not a JSON object")
        }
        val inner = trimmed.substring(1, trimmed.length - 1)
        val map = LinkedHashMap<String, String>()
        val regex = Regex(""""([\w-]+)"\s*:\s*("(?:[^"\\]|\\.)*"|[^,}]+)""")
        regex.findAll(inner).forEach { m ->
            val key = m.groupValues[1]
            var value = m.groupValues[2].trim()
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = unescapeJson(
                    value.substring(1, value.length - 1)
                )
            }
            map[key] = value
        }
        if (map.isEmpty()) throw ShareLinkException("vmess: empty payload")
        return map
    }

    // ========== YAML 输出 ==========

    /**
     * 输出 `proxies[]` 的一个列表项。
     *
     * 首字段必须带 `- `，否则整段会被 YAML 解析成 map 而不是数组，mihomo 直接拒绝；
     * 后续字段要对齐到首字段的键列（4 空格），嵌套层再加 2。
     */
    private fun appendProxyEntry(map: Map<String, Any?>, sb: StringBuilder) {
        var first = true
        for ((key, value) in map) {
            if (value == null) continue
            val head = if (first) "  - " else "    "
            first = false
            when (value) {
                is Map<*, *> -> {
                    sb.append(head).append(key).append(":\n")
                    @Suppress("UNCHECKED_CAST")
                    appendMap(value as Map<String, Any?>, 3, sb)
                }

                is List<*> -> {
                    if (value.isEmpty()) continue
                    sb.append(head).append(key).append(":\n")
                    value.forEach { item ->
                        sb.append("      - ").append(scalar(item)).append('\n')
                    }
                }

                else -> sb.append(head).append(key).append(": ").append(scalar(value)).append('\n')
            }
        }
    }

    private fun appendMap(map: Map<String, Any?>, indent: Int, sb: StringBuilder) {
        val pad = " ".repeat(indent * 2)
        for ((key, value) in map) {
            when (value) {
                null -> continue
                is Map<*, *> -> {
                    sb.append(pad).append(key).append(":\n")
                    @Suppress("UNCHECKED_CAST")
                    appendMap(value as Map<String, Any?>, indent + 1, sb)
                }

                is List<*> -> {
                    if (value.isEmpty()) continue
                    sb.append(pad).append(key).append(":\n")
                    value.forEach { item ->
                        if (item is Map<*, *>) {
                            // 列表项本身是 map（本解析器不产生，留作兜底）
                            sb.append(pad).append("  - \n")
                            @Suppress("UNCHECKED_CAST")
                            appendMapInline(item as Map<String, Any?>, indent + 2, sb)
                        } else {
                            sb.append(pad).append("  - ").append(scalar(item)).append('\n')
                        }
                    }
                }

                else -> sb.append(pad).append(key).append(": ").append(scalar(value)).append('\n')
            }
        }
    }

    private fun appendMapInline(map: Map<String, Any?>, indent: Int, sb: StringBuilder) {
        val pad = " ".repeat(indent * 2)
        for ((key, value) in map) {
            if (value is Map<*, *>) continue
            sb.append(pad).append(key).append(": ").append(scalar(value)).append('\n')
        }
    }

    /**
     * 字符串只有在不加引号会被 YAML 解析成别的东西时才加引号。
     * 数字/布尔形态、含 `#` `:` 等结构字符、首尾空格都必须包起来。
     */
    private fun scalar(value: Any?): String {
        val text = when (value) {
            null -> return ""
            is Boolean -> return value.toString()
            is Number -> return value.toString()
            else -> value.toString()
        }
        if (!needsQuote(text)) return text
        return "\"" + text.escapeYaml() + "\""
    }

    /** 双引号标量内的转义：反斜杠、双引号、控制字符都必须转，否则值被改变或 YAML 直接报错。 */
    private fun String.escapeYaml(): String {
        val sb = StringBuilder(length)
        for (c in this) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\x").append(c.code.toString(16).padStart(2, '0'))
                else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun needsQuote(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text != text.trim()) return true
        // 整数、小数、指数形态都会被 YAML 读成数字（"1.5" / "1e5" 同样中招）
        if (text.toLongOrNull() != null || text.toDoubleOrNull() != null) return true
        // YAML 1.1 的布尔写法是大小写无关的，TRUE / True 一样会被读成布尔
        if (text.lowercase() in setOf(
                "true", "false", "null", "~", "yes", "no", "on", "off", "y", "n"
            )
        ) return true
        if (text.startsWith("-") || text.startsWith("?") || text.startsWith(":")) return true
        return text.any { it in ":#{}[]&*!|>'\"%@`,\n\t\r" }
    }
}
