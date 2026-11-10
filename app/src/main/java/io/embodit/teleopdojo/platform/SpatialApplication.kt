package io.embodit.teleopdojo.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.teleopdojo.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
