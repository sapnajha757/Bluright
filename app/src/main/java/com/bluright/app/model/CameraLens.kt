package com.bluright.app.model

import androidx.camera.core.CameraSelector

enum class CameraLens(val selector: CameraSelector) {
    BACK(CameraSelector.DEFAULT_BACK_CAMERA),
    FRONT(CameraSelector.DEFAULT_FRONT_CAMERA);

    fun toggle(): CameraLens = if (this == BACK) FRONT else BACK
}
