package com.imnexerio.MPHolistic.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.imnexerio.MPHolistic.ApiService
import com.imnexerio.MPHolistic.PoseLandmarkerHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.imnexerio.MPHolistic.FaceLandmarkerHelper
import com.imnexerio.MPHolistic.RetrofitClient
import com.imnexerio.MPHolistic.TranslationResponse
import com.imnexerio.mpholistic.R
import com.imnexerio.mpholistic.databinding.FragmentCameraBinding
import com.imnexerio.signlanguage.HandLandmarkerHelper
import retrofit2.Call
import retrofit2.Response
import retrofit2.Callback

class CameraFragment : Fragment(), HandLandmarkerHelper.handLandmarkerListener, PoseLandmarkerHelper.poseLandmarkerListener,FaceLandmarkerHelper.faceLandmarkerListener {

    companion object {
        private const val TAG = "Hand Landmarker"
    }


    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    private var faceResults: FaceLandmarkerHelper.ResultBundle? = null

    private var handResults: HandLandmarkerHelper.ResultBundle? = null
    private var poseResults: PoseLandmarkerHelper.ResultBundle? = null


    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the HandLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (handLandmarkerHelper.isClose()) {
                handLandmarkerHelper.setupHandLandmarker()
            }
            if (poseLandmarkerHelper.isClose()) {
                poseLandmarkerHelper.setupPoseLandmarker()
            }
            if (faceLandmarkerHelper.isClose()) {
                faceLandmarkerHelper.setupFaceLandmarker()
            }
        }
    }



    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)


        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the HandLandmarkerHelper that will handle the inference
        backgroundExecutor.execute {
            handLandmarkerHelper = HandLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = 0.4f,
                minHandTrackingConfidence = 0.4f,
                minHandPresenceConfidence = 0.4f,
                maxNumHands = 2,
                currentDelegate = 0,
                handLandmarkerHelperListener = this
            )
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = 0.4f,
                minPoseTrackingConfidence = 0.4f,
                minPosePresenceConfidence = 0.4f,
                currentDelegate = 0,
                poseLandmarkerHelperListener = this
            )

            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minFaceDetectionConfidence = 0.4f,
                minFaceTrackingConfidence = 0.4f,
                minFacePresenceConfidence = 0.4f,
                maxNumFaces = 1,
                currentDelegate = 0,
                faceLandmarkerHelperListener = this
            )
        }

    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    val bitmap = image.toBitmap()
                    val matrix = Matrix().apply {
                        postRotate(image.imageInfo.rotationDegrees.toFloat())
                        if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                            postScale(1f, 1f, bitmap.width.toFloat(), bitmap.height.toFloat())
                        }
                    }
                    val rotatedBitmap = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                    detectHand(rotatedBitmap)
                    detectPose(rotatedBitmap)
                    detectFace(rotatedBitmap)
                    image.close()
                }
            }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectHand(bitmap: Bitmap) {
        handLandmarkerHelper.detectLiveStream(
            bitmap,
            cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    private fun detectPose(bitmap: Bitmap) {
        poseLandmarkerHelper.detectLiveStream(
            bitmap,
            cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    private fun detectFace(bitmap: Bitmap){
        faceLandmarkerHelper.detectLiveStream(
            bitmap,
            cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // OverlayViewHandlandmarker
    override fun onHandlandmerkerResults(
        resultBundle: HandLandmarkerHelper.ResultBundle
    ) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {

                // Pass necessary information to OverlayViewHandlandmarker for drawing on the canvas
//                fragmentCameraBinding.overlayHand.setResultsHand(
//                    resultBundle.results.first(),
//                    resultBundle.inputImageHeight,
//                    resultBundle.inputImageWidth,
//                    RunningMode.LIVE_STREAM
//                )
//
//                // Force a redraw
//                fragmentCameraBinding.overlayHand.invalidate()
            }
        }

        // Store hand results
        handResults = resultBundle
        sendCombinedResults()
    }


    override fun onHandlandmerkerError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPoseladmarkerError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPoseladmarkerResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
//                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
//                    String.format("%d ms", resultBundle.inferenceTime)

                // Pass necessary information to OverlayView for drawing on the canvas
//                fragmentCameraBinding.overlayPose.setResultsPose(
//                    resultBundle.results.first(),
//                    resultBundle.inputImageHeight,
//                    resultBundle.inputImageWidth,
//                    RunningMode.LIVE_STREAM
//                )
//
//                // Force a redraw
//                fragmentCameraBinding.overlayPose.invalidate()
            }
        }

        // Store pose results
        poseResults = resultBundle
        sendCombinedResults()
    }


    override fun onfacelandmarkerResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
//                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
//                    String.format("%d ms", resultBundle.inferenceTime)

                // Pass necessary information to OverlayView for drawing on the canvas
//                fragmentCameraBinding.overlayFace.setResultsPose(
//                    resultBundle.results.first(),
//                    resultBundle.inputImageHeight,
//                    resultBundle.inputImageWidth,
//                    RunningMode.LIVE_STREAM
//                )
//                fragmentCameraBinding.overlayFace.setResultsFace(
//                    resultBundle.result,
//                    resultBundle.inputImageHeight,
//                    resultBundle.inputImageWidth,
//                    RunningMode.LIVE_STREAM
//                )
//
//                // Force a redraw
//                fragmentCameraBinding.overlayFace.invalidate()
            }
            faceResults = resultBundle
            sendCombinedResults()
        }
    }

    override fun onfacelandmarkerError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }


    private fun sendCombinedResults() {
    if (handResults != null && poseResults != null && faceResults != null) {
        val sendHandPoseResults = handResults!!.results[0].landmarks()
        val sendPoseResults = poseResults!!.results[0].landmarks()
        val sendFaceResults = faceResults!!.result.faceLandmarks()

        val poseLandmarks = try {
            if (sendPoseResults?.isNotEmpty() == true) {
                sendPoseResults[0].subList(0, 33).map {
                    mapOf(
                        "x" to it.x(),
                        "y" to it.y(),
                        "z" to it.z(),
                        "visibility" to it.visibility().get()
                    )
                }
            } else {
                List(33) { mapOf("x" to 0.0f, "y" to 0.0f, "z" to 0.0f, "visibility" to 0.0f) }
            }
        } catch (e: Exception) {
            List(33) { mapOf("x" to 0.0f, "y" to 0.0f, "z" to 0.0f, "visibility" to 0.0f) }
        }

        val leftHandLandmarks = try {
            if (sendHandPoseResults?.isNotEmpty() == true) {
                sendHandPoseResults[0].subList(0, 21).map {
                    mapOf(
                        "x" to it.x(),
                        "y" to it.y(),
                        "z" to it.z()
                    )
                }
            } else {
                List(21) { mapOf("x" to 0.0f, "y" to 0.0f, "z" to 0.0f) }
            }
        } catch (e: Exception) {
            List(21) { mapOf("x" to 0.0f, "y" to 0.0f, "z" to 0.0f) }
        }

        val rightHandLandmarks = try {
            if (sendHandPoseResults?.size ?: 0 > 1 && sendHandPoseResults[1].isNotEmpty()) {
                sendHandPoseResults[1].subList(0, 21).map {
                    mapOf(
                        "x" to it.x(),
                        "y" to it.y(),
                        "z" to it.z()
                    )
                }
            } else {
                List(21) { mapOf("x" to 0.0f, "y" to 0.0f, "z" to 0.0f) }
            }
        } catch (e: Exception) {
            List(21) { mapOf("x" to 0.0f, "y" to 0.0f, "z" to 0.0f) }
        }

        val faceLandmarks = try {
            if (sendFaceResults?.isNotEmpty() == true) {
                sendFaceResults[0].subList(0, 468).map {
                    mapOf(
                        "x" to it.x(),
                        "y" to it.y(),
                        "z" to it.z()
                    )
                }
            } else {
                List(468) { mapOf("x" to 0.0f, "y" to 0.0f, "z" to 0.0f) }
            }
        } catch (e: Exception) {
            List(468) { mapOf("x" to 0.0f, "y" to 0.0f, "z" to 0.0f) }
        }

        val combinedResults = mapOf(
            "keypoints" to mapOf(
                "poseLandmarks" to poseLandmarks,
                "leftHandLandmarks" to leftHandLandmarks,
                "rightHandLandmarks" to rightHandLandmarks,
                "faceLandmarks" to faceLandmarks
            )
        )

        // Create a custom Gson instance
        val gson = GsonBuilder()
            .disableHtmlEscaping()
            .create()

        // Convert to JSON string
        val jsonString = gson.toJson(combinedResults)

        // Format the JSON string to match the required format
        val formattedJson = jsonString
            .replace("\"", "'")
            .replace(": ", ":")
            .replace(",", ", ")


        // Convert JSON string to JsonObject
        val formattedJson1 = gson.fromJson(formattedJson, JsonObject::class.java)


        Log.i(TAG, "Formatted JSON: $formattedJson")

        // Make the network request
        val apiService = RetrofitClient.instance.create(ApiService::class.java)
        val call = apiService.uploadKeypoints(formattedJson1)
        call.enqueue(object : Callback<TranslationResponse> {
            override fun onResponse(call: Call<TranslationResponse>, response: Response<TranslationResponse>) {
                if (response.isSuccessful) {
                    val translatedText = response.body()?.translatedText
                    activity?.runOnUiThread {
                        fragmentCameraBinding.translatedTextView.text = translatedText.toString()
                    }
                    // Handle the translated text
                } else {
                    Log.e(TAG, "Request failed with code: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<TranslationResponse>, t: Throwable) {
                Log.e(TAG, "Network request failed", t)
            }
        })

        // Clear the stored results after sending
        handResults = null
        poseResults = null
        faceResults = null
    }
}
}
