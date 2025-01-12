package io.papermc.paperweight.checkstyle

data class JavadocTag(val tag: String, val appliesTo: String, val prefix: String) {
    fun toOptionString(): String {
        return "$tag:$appliesTo:$prefix"
    }
}

fun PaperCheckstyleTask.setCustomJavadocTags(tags: Iterable<JavadocTag>) {
    configProperties = (configProperties ?: emptyMap()).toMutableMap().apply {
        this["custom_javadoc_tags"] = tags.joinToString("|") { it.toOptionString() }
    }
}
