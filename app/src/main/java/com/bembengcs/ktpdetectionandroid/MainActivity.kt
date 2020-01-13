package com.bembengcs.ktpdetectionandroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.alert
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LifecycleOwner {

  companion object {
    private const val REQUEST_CODE_PERMISSIONS = 42
    private val REQUIRED_PERMISSIONS = arrayOf(
      Manifest.permission.CAMERA,
      Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
  }

  private lateinit var viewFinder: TextureView
  private val executor = Executors.newSingleThreadExecutor()
  private var rotation = 0
  private var filePath = ""
  private var ktp = ""
  private val downloadDirectory =
    Environment.getExternalStorageDirectory().toString() + "/CameraX"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    viewFinder = findViewById(R.id.viewFinder)

    viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
      updateTransform()
    }

    if (allPermissionsGranted()) {
      viewFinder.post { startCamera() }
    } else {
      ActivityCompat.requestPermissions(
        this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
      )
    }
  }

  private fun startCamera() {
    val preview = setupPreview()
    val imageCapture = setupImageCapture()
    val analyzerUseCase = setupAnalyzer()

    CameraX.bindToLifecycle(this, preview, imageCapture, analyzerUseCase)
  }

  private fun setupImageCapture(): ImageCapture {
    val imageCaptureConfig = ImageCaptureConfig.Builder()
      .apply {
        setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
      }.build()

    val imageCapture = ImageCapture(imageCaptureConfig)
    btnCapture.setOnClickListener {
      onCaptureButtonClicked(imageCapture)
    }

    return imageCapture
  }

  private fun onCaptureButtonClicked(imageCapture: ImageCapture) {
    setupDirectory()

    val file = File(this.downloadDirectory + "/${System.currentTimeMillis()}.jpg")

    imageCapture.takePicture(file, executor,
      object : ImageCapture.OnImageSavedListener {
        override fun onError(
          imageCaptureError: ImageCapture.ImageCaptureError,
          message: String,
          exc: Throwable?
        ) {
          imageSaveFailed(message, exc!!)
        }

        override fun onImageSaved(file: File) {
          imageSaveSuccess(file)
        }
      })
  }

  private fun imageSaveFailed(message: String, exc: Throwable) {
    val msg = "Photo capture failed: $message"
    Log.e("CameraXApp", msg, exc)
    viewFinder.post {
      Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
    }
  }

  private fun imageSaveSuccess(file: File) {
    val msg = "Photo capture succeeded: ${file.absolutePath}"
    Log.d("CameraXApp", msg)
    viewFinder.post {
      Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
    }

    filePath = file.absolutePath

    val byteArray = File(filePath).readBytes()
    saveBitmap(croppedImage(byteArray))
  }

  private fun setupDirectory() {
    val downloadDirectory = File(downloadDirectory)
    if (!downloadDirectory.exists()) {
      downloadDirectory.mkdirs()
    } else {
      downloadDirectory.deleteRecursively()
      downloadDirectory.mkdirs()
    }
  }

  private fun saveBitmap(bmp: Bitmap): File {
    val bytes = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 60, bytes)
    val file = File(this.downloadDirectory + "/${System.currentTimeMillis()}.jpg")
    file.createNewFile()
    FileOutputStream(file).apply {
      write(bytes.toByteArray())
      close()
    }

    recognizeText(file.absolutePath)
    return file
  }

  private fun recognizeText(filePath: String) {
    val myBitmap = BitmapFactory.decodeFile(filePath)
    val textImage = FirebaseVisionImage.fromBitmap(myBitmap)
    val textRecognizer = FirebaseVision.getInstance().onDeviceTextRecognizer

    textRecognizer.processImage(textImage)
      .addOnSuccessListener {
        for (block in it.textBlocks) {
          for (line in block.lines) {
            Log.d("line", line.text)
            if (!isNumeric(line.text)) {
              Log.d("not numbers", line.text)
            } else {
              Log.d("numbers", line.text)
            }
            for (element in line.elements) {
              showKTP(element.text)
            }
          }
        }

        if (ktp == "") Toast.makeText(
          this@MainActivity,
          "KTP not found",
          Toast.LENGTH_LONG
        ).show()
      }
      .addOnFailureListener {

      }
  }

  private fun isNumeric(text: String): Boolean {
    return text.matches("-?\\d+(\\.\\d+)?".toRegex())
  }

  private fun showKTP(elementText: String) {

    Log.d("element", elementText)

    if (isNumeric(elementText)) {
      if (elementText.length == 16) {
        runOnUiThread {
          ktp = elementText
          alert(elementText, "No. KTP").show()
        }
      }
    }
  }

  private fun croppedImage(data: ByteArray): Bitmap {
    val imageOriginal = BitmapFactory.decodeByteArray(data, 0, data.size, null)

    val matrix = Matrix()
    matrix.postRotate(90F)
    val rotatedBitmap = Bitmap.createBitmap(
      imageOriginal, 0, 0, imageOriginal.width,
      imageOriginal.height, matrix, false
    )

    val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
    val scale = metrics.widthPixels / 1000F
    val width = scale * ktpViewer.width.toPx()
    val height = scale * ktpViewer.height.toPx()
    val x = scale * (rotatedBitmap.width - ktpViewer.width.toPx()) / 2
    val y = scale * (rotatedBitmap.height - ktpViewer.height.toPx()) / 2

    return Bitmap.createBitmap(
      rotatedBitmap, x.toInt(), y.toInt(), width.toInt(),
      height.toInt(), null, false
    )
  }

  private fun Int.toPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

  private fun setupAnalyzer(): ImageAnalysis {
    val analyzerConfig = ImageAnalysisConfig.Builder().apply {
      setImageReaderMode(
        ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE
      )
    }.build()

    return ImageAnalysis(analyzerConfig).apply {
      setAnalyzer(executor, LuminosityAnalyzer())
    }
  }

  private fun setupPreview(): Preview {

    val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
    val screenSize = Size(metrics.widthPixels, metrics.heightPixels)

    Log.d("screenSize", screenSize.toString())

    val previewConfig = PreviewConfig.Builder().apply {
      setTargetResolution(screenSize)
      setTargetRotation(viewFinder.display.rotation)
    }.build()

    val preview = Preview(previewConfig)

    preview.setOnPreviewOutputUpdateListener {

      // To update the SurfaceTexture, we have to remove it and re-add it
      val parent = viewFinder.parent as ViewGroup
      parent.removeView(viewFinder)
      parent.addView(viewFinder, 0)

      viewFinder.surfaceTexture = it.surfaceTexture
      updateTransform()
    }

    return preview
  }

  @SuppressLint("ObsoleteSdkInt")
  private fun updateTransform() {
    val matrix = Matrix()

    //Find the center
    val centerX = viewFinder.width / 2f
    val centerY = viewFinder.height / 2f

    //Get correct rotation
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      rotation = when (viewFinder.display.rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> return
      }
    }

    matrix.postRotate(-rotation.toFloat(), centerX, centerY)

    viewFinder.setTransform(matrix)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    if (requestCode == REQUEST_CODE_PERMISSIONS) {
      if (allPermissionsGranted()) {
        startCamera()
      } else {
        Toast.makeText(
          this,
          "Permissions not granted by the user.",
          Toast.LENGTH_SHORT
        ).show()
        finish()
      }
    }
  }

  private fun allPermissionsGranted(): Boolean {
    for (permission in REQUIRED_PERMISSIONS) {
      if (ContextCompat.checkSelfPermission(
          this, permission
        ) != PackageManager.PERMISSION_GRANTED
      ) {
        return false
      }
    }
    return true
  }
}
