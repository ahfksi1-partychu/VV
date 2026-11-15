package com.mycarhub

import android.app.Application
import com.mycarhub.apps.FavoriteAppsManager
import com.mycarhub.apps.data.SharedPrefsFavoriteAppStore
import com.mycarhub.apps.system.AppLauncher
import com.mycarhub.apps.system.CarRestrictionMonitor
import com.mycarhub.apps.system.InstalledAppCatalog
import com.mycarhub.assistant.messages.MessageAccess
import com.mycarhub.assistant.stt.SttSettings
import com.mycarhub.platform.Platform
import com.mycarhub.wakeword.WakeWordManager
import com.mycarhub.wakeword.audio.DefaultAudioSourceProvider
import com.mycarhub.wakeword.data.WakeWordRepository

/** 앱 전역 객체. 메인 화면과 음성비서 화면이 같은 FavoriteAppsManager·AppLauncher·WakeWordManager를 공유한다. */
class MyCarHubApplication : Application() {

    lateinit var restrictions: CarRestrictionMonitor
        private set
    lateinit var catalog: InstalledAppCatalog
        private set
    lateinit var launcher: AppLauncher
        private set
    lateinit var favoriteApps: FavoriteAppsManager
        private set
    lateinit var wakeWords: WakeWordManager
        private set
    lateinit var sttSettings: SttSettings
        private set

    /** 메시지 알림 읽기·답장. 폰 테스트용은 알림 접근, 차량용은 미지원 (src/car, src/phone 의 Platform). */
    lateinit var messages: MessageAccess
        private set

    override fun onCreate() {
        super.onCreate()
        restrictions = CarRestrictionMonitor(this).also { it.connect() }
        catalog = InstalledAppCatalog(this)
        launcher = AppLauncher(this, catalog, restrictions)
        favoriteApps = FavoriteAppsManager(
            store = SharedPrefsFavoriteAppStore(this),
            catalog = catalog,
            launcher = launcher,
        )
        wakeWords = WakeWordManager(
            context = this,
            repository = WakeWordRepository(this),
            audio = DefaultAudioSourceProvider(this),
        )
        sttSettings = SttSettings(this)
        messages = Platform.messageAccess(this)
        Platform.onAppCreate(this)
        // 차량 ↔ 휴대폰 동기화: 페어링되어 있으면 시작 (서버 설정이 없는 빌드는 아무것도 하지 않음)
        com.mycarhub.drive.DriveSyncManager.start(this)
    }
}
