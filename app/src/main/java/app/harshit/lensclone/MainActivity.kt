/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.harshit.lensclone

import android.Manifest
import android.content.pm.PackageManager
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.design.widget.BottomSheetBehavior
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.View

import com.google.android.things.contrib.driver.button.Button
import com.google.android.things.contrib.driver.button.ButtonInputDriver
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.label.FirebaseVisionCloudImageLabelerOptions
import com.google.firebase.ml.vision.label.FirebaseVisionImageLabel
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.bottom_sheet.*


import java.io.IOException

/**
 * Doorbell activity that capture a picture from an Android Things
 * camera on a button press and post it to Firebase and Google Cloud
 * Vision API.
 */
class MainActivity : AppCompatActivity() {

    var isDetecting = false

    private var mCamera: LensCamera? = null

    /**
     * Driver for the doorbell button;
     */
    private var mButtonInputDriver: ButtonInputDriver? = null

    /**
     * A [Handler] for running Camera tasks in the background.
     */
    private var mCameraHandler: Handler? = null

    /**
     * An additional thread for running Camera tasks that shouldn't block the UI.
     */
    private var mCameraThread: HandlerThread? = null

    /**
     * A [Handler] for running Cloud tasks in the background.
     */
    private var mCloudHandler: Handler? = null

    /**
     * An additional thread for running Cloud tasks that shouldn't block the UI.
     */
    private var mCloudThread: HandlerThread? = null

    /**
     * Listener for new camera images.
     */

    lateinit var sheetBehavior: BottomSheetBehavior<*>

    private val detectedLabels = arrayListOf<FirebaseVisionImageLabel>()

    val imageLabelAdapter = ImageLabelAdapter(detectedLabels)

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // We need permission to access the camera

        sheetBehavior = BottomSheetBehavior.from(bottomLayout)
        sheetBehavior.isHideable = true
        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        rvLabel.layoutManager = LinearLayoutManager(this)
        rvLabel.adapter = imageLabelAdapter

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // A problem occurred auto-granting the permission
            Log.e(TAG, "No permission")
            return
        }

        // Creates new handlers and associated threads for camera and networking operations.
        mCameraThread = HandlerThread("CameraBackground")
        mCameraThread!!.start()
        mCameraHandler = Handler(mCameraThread!!.looper)

        mCloudThread = HandlerThread("CloudThread")
        mCloudThread!!.start()
        mCloudHandler = Handler(mCloudThread!!.looper)

        // Initialize the doorbell button driver
        initPIO()

        // Camera code is complicated, so we've shoved it all in this closet class for you.
        mCamera = LensCamera.getInstance()
        mCamera!!.initializeCamera(this, mCameraHandler, mOnImageAvailableListener)
    }

    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        fetchLabels(image)
    }

    private fun fetchLabels(image: Image) {
        val firebaseVisionImage = FirebaseVisionImage.fromMediaImage(image, Surface.ROTATION_0)

        val debugBitmap = firebaseVisionImage.bitmapForDebugging

        runOnUiThread {
            ivPreview.setImageBitmap(debugBitmap)
        }

        val options = FirebaseVisionCloudImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.7f)
                .build()
        val labeler = FirebaseVision.getInstance()
                .getCloudImageLabeler(options)

        labeler.processImage(firebaseVisionImage)
                .addOnSuccessListener {
                    detectedLabels.clear()
                    detectedLabels.addAll(it)
                    imageLabelAdapter.notifyDataSetChanged()
                    sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
                .addOnCompleteListener {
                    image.close()
                    isDetecting = false
                    progress.visibility = View.GONE
                }

    }

    private fun initPIO() {
        try {
            mButtonInputDriver = ButtonInputDriver(
                    BoardDefaults.getGPIOForButton(),
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_ENTER)
            mButtonInputDriver!!.register()
        } catch (e: IOException) {
            mButtonInputDriver = null
            Log.w(TAG, "Could not open GPIO pins", e)
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        mCamera!!.shutDown()

        mCameraThread!!.quitSafely()
        mCloudThread!!.quitSafely()
        try {
            mButtonInputDriver!!.close()
        } catch (e: IOException) {
            Log.e(TAG, "button driver error", e)
        }

    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            // Doorbell rang!
            Log.d(TAG, "button pressed")
            if (!isDetecting) {
                mCamera!!.takePicture()
                sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                progress.visibility = View.VISIBLE
            }
            isDetecting = true
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private val TAG = "MainActivity"
    }

}
