package com.heydusk.mimi

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt 的根。依赖注入只在 :app 里装配；:core:* 模块保持无框架。 */
@HiltAndroidApp
class MimiApplication : Application()
