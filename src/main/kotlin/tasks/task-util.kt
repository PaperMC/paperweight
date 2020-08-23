package io.papermc.paperweight.tasks

import org.gradle.api.Task
import org.gradle.api.provider.Property

fun Task.finalizeProperties() {
    for (field in this.javaClass.fields) {
        if (Property::class.java.isAssignableFrom(field.type)) {
            (field.get(this) as Property<*>).finalizeValue()
        }
    }
}
