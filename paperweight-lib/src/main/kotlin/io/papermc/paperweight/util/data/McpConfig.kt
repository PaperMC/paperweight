package io.papermc.paperweight.util.data

data class McpConfig(
    val functions: McpConfigFunctions
)

data class McpConfigFunctions(
    val decompile: McpConfigFunction,
    val merge: McpConfigFunction,
    val rename: McpConfigFunction,
    val mergeMappings: McpConfigFunction,
    val bundleExtractJar: McpConfigFunction
)

data class McpConfigFunction(
    val version: String,
    val args: List<String>,
    val jvmargs: List<String>,
    val repo: String
)
