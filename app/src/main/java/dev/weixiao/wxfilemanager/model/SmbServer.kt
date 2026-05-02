package dev.weixiao.wxfilemanager.model

data class SmbServer(
    val host: String,
    val user: String,
    val pass: String,
    val name: String = host
)
