package com.zgsbrgr.dev.compose.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun Capture(
    modifier: Modifier,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    onCapturedImage: (File) -> Unit = { }
    ) {

    val context = LocalContext.current

    Box(modifier = modifier) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val coroutineScope = rememberCoroutineScope()
        var previewUseCase by remember { mutableStateOf<UseCase>(Preview.Builder().build()) }
        val imageCaptureUseCase by remember {
            mutableStateOf(
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
            )
        }
        val imageAnalysisUseCase by remember {
            mutableStateOf(
                ImageAnalysis.Builder()
                    .setTargetResolution(Size(640,480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

            )
        }

        val (clippingHeight, clippingWidth) = remember {
            Animatable(0f) to Animatable(0f)
        }

        val lineOffsetY = remember {
            Animatable(500f)
        }

        val lineAlpha = remember {
            Animatable(0f)
        }

        val lineColor = colorResource(id = R.color.color_dark_blue)

        Box {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onUseCase = {
                    previewUseCase = it
                }
            )
            Canvas(modifier = Modifier.fillMaxSize(), onDraw = {

                val rectPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            center.x - clippingWidth.value.toInt(),
                            center.y - clippingHeight.value.toInt(),
                            center.x + clippingWidth.value.toInt(),
                            center.y + clippingHeight.value.toInt(),
                            CornerRadius(50f,50f),
                            CornerRadius(50f,50f),
                            CornerRadius(50f,50f),
                            CornerRadius(50f,50f),
                            )
                    )
                }

                clipPath(rectPath, clipOp = ClipOp.Difference) {
                    drawRect(SolidColor(Color.Black.copy(alpha = 0.3f)))
                }
                drawLine(
                    lineColor,
                    Offset((center.x - 450), center.y - lineOffsetY.value),
                    Offset((center.x + 450), center.y - lineOffsetY.value),
                    15f,
                    alpha = lineAlpha.value

                )
            })
            CaptureButton(
                modifier = Modifier
                    .size(100.dp)
                    .padding(16.dp)
                    .align(Alignment.BottomCenter),
                onCaptureClick = {
                    coroutineScope.launch {
                        imageCaptureUseCase.takePicture(context.executor).let {
                            onCapturedImage(it)
                        }
                    }
                }
            )
        }
        LaunchedEffect(previewUseCase) {
            launch {
               clippingHeight.animateTo(
                    600f,
                    tween(500, 400)
                )
            }
            launch {
                clippingWidth.animateTo(
                    450f,
                    tween(500, 400)
                )
            }

            launch {
                lineAlpha.animateTo(
                    1f,
                    tween(300, 700)
                )
                lineOffsetY.animateTo(
                    -500f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(850, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )

                )
            }



            imageAnalysisUseCase.analyzeImage(context.executor)

            //val viewPort = ViewPort.Builder(Rational(250, 100), Surface.ROTATION_90).build()
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(previewUseCase)
                .addUseCase(imageAnalysisUseCase)
                .addUseCase(imageCaptureUseCase)
                //.setViewPort(viewPort)
                .build()

            val cameraProvider = context.getCameraProvider()

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, useCaseGroup
                )
//                cameraProvider.bindToLifecycle(
//                    lifecycleOwner, cameraSelector, previewUseCase, imageCaptureUseCase
//                )
            } catch (ex: Exception) {
                Log.e("CameraCapture", "Failed to bind camera use cases", ex)
            }
        }
    }
}

suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { cameraProvider ->
        cameraProvider.addListener({
            continuation.resume(cameraProvider.get())
        }, ContextCompat.getMainExecutor(this))
    }
}

val Context.executor: Executor
    get() = ContextCompat.getMainExecutor(this)

@SuppressLint("UnsafeOptInUsageError")
suspend fun ImageAnalysis.analyzeImage(executor: Executor): String {
    return withContext(Dispatchers.IO) {
        setAnalyzer(executor) { imageProxy ->
            imageProxy.image?.let {
                Log.d("ImageAnalysis", it.timestamp.toString())
            }
            imageProxy.close()
        }

        ""
    }

}

suspend fun ImageCapture.takePicture(executor: Executor): File {
    val photoFile = withContext(Dispatchers.IO) {
        kotlin.runCatching {
            File.createTempFile("image", "jpg")
        }.getOrElse { ex ->
            Log.e("Capture", "Failed to create temporary file", ex)
            File("/dev/null")
        }
    }

    return suspendCoroutine { continuation ->
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        takePicture(
            outputOptions, executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    continuation.resume(photoFile)
                }

                override fun onError(ex: ImageCaptureException) {
                    Log.e("Capture", "Image capture failed", ex)
                    continuation.resumeWithException(ex)
                }
            }
        )
    }
}