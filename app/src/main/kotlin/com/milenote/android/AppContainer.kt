package com.milenote.android

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.milenote.android.data.garage.GarageFileStore
import com.milenote.android.data.garage.GarageRepository
import com.milenote.android.data.share.SharedImport
import com.milenote.android.data.settings.SettingsRepository
import com.milenote.android.data.webdav.WebDavAutoSyncEngine
import com.milenote.android.data.webdav.WebDavRepository
import com.milenote.android.navigation.AppShortcut

/** 轻量手工依赖容器（单 Activity 应用，暂不引入 DI 框架）。 */
class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)
    val garageRepository: GarageRepository = GarageRepository(GarageFileStore(context.applicationContext))
    val webDavRepository: WebDavRepository = WebDavRepository(context.applicationContext)
    val webDavAutoSyncEngine: WebDavAutoSyncEngine = WebDavAutoSyncEngine(garageRepository, webDavRepository)

    /** 系统分享进来待处理的导入内容（MainActivity 写入，导入向导消费）。 */
    val pendingShare = kotlinx.coroutines.flow.MutableStateFlow<SharedImport?>(null)

    /** 桌面长按快捷方式待处理的入口（MainActivity 写入，界面消费后打开对应编辑器）。 */
    val pendingShortcut = kotlinx.coroutines.flow.MutableStateFlow<AppShortcut?>(null)

    init {
        // 车辆图片可能是 http(s) URL：显式注册 OkHttp fetcher（dataURL 走本地解码，不需要网络）。
        SingletonImageLoader.setSafe { appContext ->
            ImageLoader.Builder(appContext)
                .components { add(OkHttpNetworkFetcherFactory()) }
                .build()
        }
    }
}
