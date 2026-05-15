buildscript {
    extra.apply {
        set("kotlinVersion", "1.9.22")
        set("composeVersion", "1.5.8")
    }
}

plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.chaquo.python") version "15.0.0" apply false
}
