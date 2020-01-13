package com.bembengcs.ktpdetectionandroid

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Created by bembengcs on 2020-01-14.
 * Android Engineer
 */
class LuminosityAnalyzer: ImageAnalysis.Analyzer {
  private var lastAnalyzedTimestamp = 0L

  private fun ByteBuffer.toByteArray(): ByteArray {
    rewind()
    val data = ByteArray(remaining())
    get(data)
    return data
  }

  override fun analyze(image: ImageProxy, rotationDegrees: Int) {
    val currentTimestamp = System.currentTimeMillis()
    if (currentTimestamp - lastAnalyzedTimestamp >=
      TimeUnit.SECONDS.toMillis(1)) {
      val buffer = image.planes[0].buffer
      val data = buffer.toByteArray()
      val pixels = data.map { it.toInt() and 0xFF }
      val luma = pixels.average()
      Log.d("CameraXApp", "Average luminosity: $luma")
      lastAnalyzedTimestamp = currentTimestamp
    }
  }

}
