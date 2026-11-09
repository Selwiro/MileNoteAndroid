package com.milenote.android

import android.app.Application
import com.milenote.android.platform.ocr.TesseractOcrRecognizer

class MileNoteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // OCR 引擎需要 Application Context 定位内置模型与可写目录（接口本身不带 Context）。
        TesseractOcrRecognizer.install(this)
        container = AppContainer(this)

    }
}
