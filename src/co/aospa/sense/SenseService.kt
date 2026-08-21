package co.aospa.sense

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Camera
import android.hardware.biometrics.BiometricFaceConstants
import android.hardware.camera2.CameraManager
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat

import co.aospa.sense.controller.FaceAuthenticationController
import co.aospa.sense.controller.FaceEnrollController
import co.aospa.sense.camera.CameraUtil
import co.aospa.sense.util.Constants
import co.aospa.sense.util.PreferenceHelper
import co.aospa.sense.util.SenseUserContext
import co.aospa.sense.util.Util
import co.aospa.sense.vendor.Vendor
import co.aospa.sense.vendor.VendorImpl

import java.lang.StringBuilder
import java.util.*

import vendor.aospa.biometrics.face.ISenseService
import vendor.aospa.biometrics.face.ISenseServiceReceiver

class SenseService : Service() {

    private lateinit var mIdleTimeoutIntent: PendingIntent
    private lateinit var mLockoutTimeoutIntent: PendingIntent
    private var mAlarmManager: AlarmManager? = null
    private var mCameraAuthController: FaceAuthenticationController? = null
    private var mCameraEnrollController: FaceEnrollController? = null
    private var mCameraManager: CameraManager? = null
    private var mSenseReceiver: ISenseServiceReceiver? = null
    private lateinit var mUserContext: Context
    private var mPreferenceHelper: PreferenceHelper? = null
    private var mVendorImpl: Vendor? = null
    private val mUserStates = mutableMapOf<Int, UserState>()
    private var mIsVendorInit = false
    private var mCameraId = 0
    private var mChallengeCount = 0
    private var mUserId = 0
    private var mAuthenticationErrorCount = 0
    private var mAuthenticationErrorThrottleCount = 0
    private var mLockoutType = LOCKOUT_TYPE_DISABLED
    private var mChallenge: Long = 0
    private var mEnrollToken: ByteArray? = null
    private var mOnIdleTimer = false
    private var mOnLockoutTimer = false
    private var mUserUnlocked = false
    private var mIsAuthenticated = false

    private val mLockoutLock = Any()
    private val mAuthErrorLock = Any()

    private data class UserState(
        var authenticationErrorCount: Int = 0,
        var authenticationErrorThrottleCount: Int = 0,
        var lockoutType: Int = LOCKOUT_TYPE_DISABLED,
        var onIdleTimer: Boolean = false,
        var onLockoutTimer: Boolean = false,
        var userUnlocked: Boolean = false
    )

    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (Util.IS_DEBUG_LOGGING) {
                Log.d(TAG, "OnReceive intent = $intent")
            }
            val userId = intent.getIntExtra(Intent.EXTRA_USER_ID, mUserId)
            val state = mUserStates[userId]
            when (action) {
                ALARM_TIMEOUT_FREEZED -> if (userId == mUserId) {
                    synchronized(mLockoutLock) { mLockoutType = LOCKOUT_TYPE_IDLE }
                } else {
                    state?.lockoutType = LOCKOUT_TYPE_IDLE
                    state?.onIdleTimer = false
                }
                ALARM_FAIL_TIMEOUT_LOCKOUT -> if (userId == mUserId) {
                    cancelLockoutTimer()
                    synchronized(mLockoutLock) { mLockoutType = LOCKOUT_TYPE_DISABLED }
                    synchronized(mAuthErrorLock) { mAuthenticationErrorCount = 0 }
                } else {
                    state?.lockoutType = LOCKOUT_TYPE_DISABLED
                    state?.authenticationErrorCount = 0
                    state?.onLockoutTimer = false
                }
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> {
                    mUserUnlocked = action == Intent.ACTION_USER_PRESENT
                    updateTimersAndLockout()
                }
            }
        }
    }

    private val mCameraAvailabilityCallback: CameraManager.AvailabilityCallback =
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                super.onCameraAvailable(cameraId)
                if (mCameraId == cameraId.toInt() && mIsAuthenticated) {
                    mCameraManager!!.unregisterAvailabilityCallback(this)
                    mIsAuthenticated = false
                    onAuthenticated()
                }
            }

            override fun onCameraUnavailable(cameraId: String) {
                super.onCameraUnavailable(cameraId)
            }
        }

    private val mCameraAuthControllerCallback: FaceAuthenticationController.ServiceCallback =
        object : FaceAuthenticationController.ServiceCallback {
            override fun handlePreviewData(data: ByteArray?, width: Int, height: Int): Int {
                val imageData = IntArray(20)
                if (Util.IS_DEBUG_LOGGING) {
                    Log.d(TAG, "handleData start")
                }
                val result = mVendorImpl!!.compare(data, width, height, 0, true, true, imageData)
                if (Util.IS_DEBUG_LOGGING) Log.d(
                    TAG, "handlePreviewData result = " + result +
                            " run: fake = " + imageData[0] + ", low = " + imageData[1] +
                            ", compare score:" + imageData[2] + " live score:" + imageData[3].toDouble() / 100.0
                )
                synchronized(this) {
                    if (mCameraAuthController == null) {
                        return -1
                    }
                    if (result == 0) {
                        mIsAuthenticated = true
                        mCameraAuthController!!.stop()
                    }
                }
                return result
            }

            override fun setDetectArea(size: Camera.Size?) {
                mVendorImpl!!.setDetectArea(0, 0, size!!.height, size.width)
            }

            override fun onTimeout(withFace: Boolean) {
                if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "onTimeout, withFace=$withFace")
                try {
                    if (withFace) {
                        increaseAndCheckLockout()
                    }
                    if (mLockoutType != LOCKOUT_TYPE_DISABLED) {
                        sendLockoutError()
                    } else {
                        mSenseReceiver?.onAuthenticated(
                            0, -1, mPreferenceHelper?.getByteArrayValueByKey(
                                Constants.SHARED_KEY_ENROLL_TOKEN
                            )
                        )
                    }
                    stopAuthentication()
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
                stopAuthentication()
            }

            override fun onCameraError() {
                try {
                    mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_CANCELED, 0)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
                stopAuthentication()
            }
        }

    private val mCameraEnrollServiceCallback: FaceEnrollController.CameraCallback = object :
        FaceEnrollController.CameraCallback {
        val FEATURE_SIZE = 10000
        val mImage = ByteArray(40000)
        val mSavedFeature = ByteArray(FEATURE_SIZE)
        override fun handleSaveFeatureResult(i: Int) {}
        override fun onFaceDetected() {}
        override fun handleSaveFeature(
            data: ByteArray?,
            width: Int,
            height: Int,
            angle: Int
        ): Int {
            val imageData = IntArray(1)
            val result = mVendorImpl!!.saveFeature(
                data,
                width,
                height,
                angle,
                true,
                mSavedFeature,
                mImage,
                imageData
            )
            synchronized(this) {
                if (mCameraEnrollController == null) {
                    return -1
                }
                try {
                    val faceIds = imageData[0] + 1
                    if (result == 0) {
                        val faceId: Int =
                            mPreferenceHelper!!.getIntValueByKey(Constants.SHARED_KEY_FACE_ID)
                        if (faceId > 0) {
                            mVendorImpl!!.deleteFeature(faceId)
                        }
                        mPreferenceHelper!!.saveIntValue(Constants.SHARED_KEY_FACE_ID, faceIds)
                        mPreferenceHelper!!.saveByteArrayValue(
                            Constants.SHARED_KEY_ENROLL_TOKEN,
                            mEnrollToken
                        )
                        Util.setFaceUnlockAvailable(mUserContext)
                        stopEnroll()
                        mSenseReceiver?.onEnrollResult(faceIds, mUserId, 0)
                    } else if (result == 19) {
                        mSenseReceiver?.onEnrollResult(faceIds, mUserId, 1)
                    }
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
                return result
            }
        }

        override fun setDetectArea(size: Camera.Size?) {
            mVendorImpl!!.setDetectArea(0, 0, size!!.height, size.width)
        }

        override fun onTimeout() {
            try {
                stopEnroll()
                mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_TIMEOUT, 0)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }

        override fun onCameraError() {
            try {
                stopEnroll()
                mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_CANCELED, 0)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
    }
    private var mWorkHandler: FaceHandler? = null
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder {
        val userId = intent.getIntExtra(Intent.EXTRA_USER_ID, UserHandle.USER_SYSTEM)
        if (Util.IS_DEBUG_LOGGING) Log.i(TAG, "onBind for user $userId")
        return SenseServiceWrapper(userId)
    }

    override fun onCreate() {
        super.onCreate()
        if (Util.IS_DEBUG_LOGGING) Log.i(TAG, "onCreate")
        mCameraManager = getSystemService(CameraManager::class.java)
        mCameraId = CameraUtil.getCameraId(this)
        val handlerThread = HandlerThread(TAG, -2)
        handlerThread.start()
        mWorkHandler = FaceHandler(handlerThread.looper)
        mAlarmManager = getSystemService(AlarmManager::class.java)
        activateUser(UserHandle.USER_SYSTEM)
        val intentFilter = IntentFilter()
        intentFilter.addAction(ALARM_TIMEOUT_FREEZED)
        intentFilter.addAction(ALARM_FAIL_TIMEOUT_LOCKOUT)
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(Intent.ACTION_USER_PRESENT)
        intentFilter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        registerReceiver(mReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        if (Util.IS_DEBUG_LOGGING) {
            Log.d(TAG, "OnCreate end")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Util.IS_DEBUG_LOGGING) {
            Log.d(TAG, "onDestroy")
        }
        mVendorImpl?.release()
        unregisterReceiver(mReceiver)
    }

    @Synchronized
    private fun activateUser(userId: Int) {
        if (mPreferenceHelper != null && mUserId == userId) {
            return
        }

        if (mPreferenceHelper != null) {
            stopCurrentWork()
            mWorkHandler!!.removeMessages(MSG_CHALLENGE_TIMEOUT)
            mUserStates[mUserId] = UserState(
                mAuthenticationErrorCount,
                mAuthenticationErrorThrottleCount,
                mLockoutType,
                mOnIdleTimer,
                mOnLockoutTimer,
                mUserUnlocked
            )
            mVendorImpl?.release()
        }

        val state = mUserStates.getOrPut(userId) { UserState() }
        mUserId = userId
        mUserContext = SenseUserContext(this, userId)
        mPreferenceHelper = PreferenceHelper(mUserContext)
        mVendorImpl = VendorImpl(mUserContext)
        mAuthenticationErrorCount = state.authenticationErrorCount
        mAuthenticationErrorThrottleCount = state.authenticationErrorThrottleCount
        mLockoutType = state.lockoutType
        mOnIdleTimer = state.onIdleTimer
        mOnLockoutTimer = state.onLockoutTimer
        mUserUnlocked = state.userUnlocked
        mIdleTimeoutIntent = timerIntent(ALARM_TIMEOUT_FREEZED, userId)
        mLockoutTimeoutIntent = timerIntent(ALARM_FAIL_TIMEOUT_LOCKOUT, userId)
        mChallenge = 0
        mChallengeCount = 0
        mEnrollToken = null
        mIsAuthenticated = false
        mIsVendorInit = false
        mWorkHandler!!.post { initVendor() }
    }

    private fun timerIntent(action: String, userId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            applicationContext,
            userId,
            Intent(action).putExtra(Intent.EXTRA_USER_ID, userId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun initVendor() {
        if (mIsVendorInit || Util.isFaceUnlockDisabledByDPM(mUserContext)) {
            return
        }
        mVendorImpl!!.init()
        mIsVendorInit = true
    }

    private fun onAuthenticated() {
        try {
            mSenseReceiver?.onAuthenticated(
                mPreferenceHelper!!.getIntValueByKey(Constants.SHARED_KEY_FACE_ID),
                mUserId,
                mPreferenceHelper!!.getByteArrayValueByKey(
                    Constants.SHARED_KEY_ENROLL_TOKEN
                )
            )
            resetLockoutCount()
            stopAuthentication()
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun stopEnroll() {
        mCameraEnrollController?.stop(mCameraEnrollServiceCallback)
        mCameraEnrollController = null
        mEnrollToken = null
        mVendorImpl!!.saveFeatureStop()
    }

    private fun stopAuthentication() {
        synchronized(this) {
            mCameraAuthController?.stop()
            mCameraAuthController = null
        }
        mVendorImpl!!.compareStop()
    }

    private fun stopCurrentWork() {
        if (mCameraAuthController != null) {
            try {
                mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_USER_CANCELED, 0)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            stopAuthentication()
        }
        if (mCameraEnrollController != null) {
            try {
                mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_USER_CANCELED, 0)
            } catch (e2: RemoteException) {
                e2.printStackTrace()
            }
            stopEnroll()
        }
    }

    private fun startIdleTimer() {
        mOnIdleTimer = true
        mAlarmManager!![AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + DEFAULT_IDLE_TIMEOUT_MS] =
            mIdleTimeoutIntent!!
    }

    private fun cancelIdleTimer() {
        mOnIdleTimer = false
        mAlarmManager!!.cancel(mIdleTimeoutIntent!!)
    }

    private fun startLockoutTimer() {
        val elapsedRealtime = SystemClock.elapsedRealtime() + FAIL_LOCKOUT_TIMEOUT_MS
        mOnLockoutTimer = true
        mAlarmManager!![AlarmManager.ELAPSED_REALTIME, elapsedRealtime] =
            mLockoutTimeoutIntent!!
    }

    private fun cancelLockoutTimer() {
        if (mOnLockoutTimer) {
            mAlarmManager!!.cancel(mLockoutTimeoutIntent!!)
            mOnLockoutTimer = false
        }
    }

    private fun increaseAndCheckLockout() {
        if (mOnLockoutTimer || mLockoutType != LOCKOUT_TYPE_DISABLED) {
            return
        }
        synchronized(mAuthErrorLock) {
            mAuthenticationErrorCount += 1
            mAuthenticationErrorThrottleCount += 1
            if (Util.IS_DEBUG_LOGGING) Log.d(
                TAG,
                "increaseAndCheckLockout, mAuthErrorCount =$mAuthenticationErrorCount, mAuthErrorThrottleCount =$mAuthenticationErrorThrottleCount"
            )
            if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "userUnlocked =$mUserUnlocked")
            if (mUserUnlocked && mAuthenticationErrorCount == MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED) {
                Log.d(TAG, "Too many attempts, lockout permanent because device is unlocked")
                synchronized(mLockoutLock) { mLockoutType = LOCKOUT_TYPE_PERMANENT }
                cancelLockoutTimer()
            } else if (mAuthenticationErrorThrottleCount == MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT) {
                synchronized(mLockoutLock) {
                    Log.d(TAG, "Too many attempts, lockout permanent")
                    mLockoutType = LOCKOUT_TYPE_PERMANENT
                }
                cancelLockoutTimer()
            } else if (mAuthenticationErrorCount == MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED) {
                synchronized(mLockoutLock) {
                    Log.d(TAG, "Too many attempts, lockout for 30s")
                    mLockoutType = LOCKOUT_TYPE_TIMED
                }
                mAuthenticationErrorCount = 0
                startLockoutTimer()
            }
        }
    }

    private fun updateTimersAndLockout() {
        if (mPreferenceHelper!!.getIntValueByKey(Constants.SHARED_KEY_FACE_ID) > -1 && !mUserUnlocked) {
            if (!mOnIdleTimer) {
                cancelIdleTimer()
                startIdleTimer()
            }
        } else {
            cancelIdleTimer()
            resetLockoutCount()
        }
    }

    private fun resetLockoutCount() {
        synchronized(mAuthErrorLock) {
            mAuthenticationErrorCount = 0
            mAuthenticationErrorThrottleCount = 0
        }
        synchronized(mLockoutLock) {
            mLockoutType = LOCKOUT_TYPE_DISABLED
        }
        cancelLockoutTimer()
    }

    private inner class SenseServiceWrapper(private val userId: Int) : ISenseService.Stub() {
        private fun activateUser() {
            this@SenseService.activateUser(userId)
        }

        override fun getFeature(feature: Int, faceId: Int): Boolean {
            activateUser()
            return false
        }

        override fun setFeature(feature: Int, enable: Boolean, cryptoToken: ByteArray?, faceId: Int) {
            activateUser()
        }

        override fun setCallback(faceServiceReceiver: ISenseServiceReceiver?) {
            mSenseReceiver = faceServiceReceiver
        }

        override fun enroll(cryptoToken: ByteArray?, timeout: Int, disabledFeatures: IntArray?) {
            activateUser()
            if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "enroll")
            if (Util.isFaceUnlockDisabledByDPM(mUserContext) || mChallenge == 0L || cryptoToken == null) {
                val sb = StringBuilder()
                sb.append("Could not enroll: ")
                sb.append("hasChallenge = ")
                sb.append(mChallenge != 0L)
                sb.append(" hasCryptoToken = ")
                sb.append(cryptoToken != null)
                Log.e(TAG, sb.toString())
                try {
                    mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_TIMEOUT, 0)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            } else {
                mEnrollToken = cryptoToken
                val faceId: Int = mPreferenceHelper!!.getIntValueByKey(Constants.SHARED_KEY_FACE_ID)
                if (faceId > 0) {
                    mVendorImpl!!.deleteFeature(faceId - 1)
                    mPreferenceHelper!!.removeSharePreferences(Constants.SHARED_KEY_FACE_ID)
                    mPreferenceHelper!!.removeSharePreferences(Constants.SHARED_KEY_ENROLL_TOKEN)
                }
                resetLockoutCount()
                mWorkHandler!!.post {
                    mVendorImpl!!.saveFeatureStart()
                    synchronized(this) {
                        if (mCameraEnrollController == null) {
                            mCameraEnrollController = FaceEnrollController.instance
                        }
                        mCameraEnrollController?.start(mCameraEnrollServiceCallback, 0)
                    }
                }
            }
        }

        override fun cancel() {
            activateUser()
            if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "cancel")
            mWorkHandler!!.post {
                if (mCameraAuthController != null) {
                    stopAuthentication()
                }
                if (mCameraEnrollController != null) {
                    stopEnroll()
                }
                try {
                    mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_CANCELED, 0)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
        }

        override fun authenticate(operationId: Long) {
            activateUser()
            mCameraManager!!.registerAvailabilityCallback(mCameraAvailabilityCallback, mWorkHandler)
            if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "authenticate")
            if (!Util.isFaceUnlockAvailable(mUserContext) ||
                ContextCompat.checkSelfPermission(
                    this@SenseService.applicationContext,
                    Manifest.permission.CAMERA
                ) != 0
            ) {
                try {
                    mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_CANCELED, 0)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            } else if (Util.isFaceUnlockDisabledByDPM(mUserContext)) {
                try {
                    mSenseReceiver?.onError(BiometricFaceConstants.FACE_ERROR_CANCELED, 0)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            } else if (mLockoutType != LOCKOUT_TYPE_DISABLED) {
                sendLockoutError()
            } else {
                mWorkHandler!!.post {
                    mVendorImpl!!.compareStart()
                    synchronized(this) {
                        if (mCameraAuthController == null) {
                            mCameraAuthController = FaceAuthenticationController(
                                this@SenseService,
                                mCameraAuthControllerCallback
                            )
                        } else {
                            mCameraAuthController!!.stop()
                        }
                        mCameraAuthController!!.start()
                    }
                }
            }
        }

        override fun remove(biometricId: Int) {
            activateUser()
            if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "remove")
            mWorkHandler!!.post {
                val faceId: Int = mPreferenceHelper!!.getIntValueByKey(Constants.SHARED_KEY_FACE_ID)
                if (!(biometricId == 0 || faceId == biometricId)) {
                    Log.e(TAG, "Removing biometricId: $biometricId")
                }
                mVendorImpl!!.deleteFeature(faceId - 1)
                mPreferenceHelper!!.removeSharePreferences(Constants.SHARED_KEY_FACE_ID)
                mPreferenceHelper!!.removeSharePreferences(Constants.SHARED_KEY_ENROLL_TOKEN)
                Util.setFaceUnlockAvailable(mUserContext)
                try {
                    if (biometricId == 0) {
                        mSenseReceiver?.onRemoved(intArrayOf(faceId), mUserId)
                    } else {
                        mSenseReceiver?.onRemoved(intArrayOf(biometricId), mUserId)
                    }
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
        }

        override fun enumerate(): Int {
            activateUser()
            val faceId: Int = mPreferenceHelper!!.getIntValueByKey(Constants.SHARED_KEY_FACE_ID)
            val faceIds = if (faceId > -1) intArrayOf(faceId) else IntArray(0)
            mWorkHandler!!.post {
                try {
                    if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "enumerate = $mSenseReceiver")
                    if (mSenseReceiver == null) {
                        try {
                            Thread.sleep(50)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                    if (mSenseReceiver != null) {
                        mSenseReceiver?.onEnumerate(faceIds, mUserId)
                    }
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
            return 0
        }

        override fun getFeatureCount(): Int {
            activateUser()
            return if (mPreferenceHelper!!.getIntValueByKey(Constants.SHARED_KEY_FACE_ID) > -1) 1 else 0
        }

        override fun generateChallenge(timeout: Int): Long {
            activateUser()
            if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "generateChallenge + $timeout")
            if (mChallengeCount <= 0 || mChallenge == 0L) {
                mChallenge = Random().nextLong()
            }
            mChallengeCount += 1
            mWorkHandler!!.removeMessages(MSG_CHALLENGE_TIMEOUT)
            mWorkHandler!!.sendEmptyMessageDelayed(MSG_CHALLENGE_TIMEOUT, timeout * 1000L)
            return mChallenge
        }

        override fun revokeChallenge(): Int {
            activateUser()
            if (Util.IS_DEBUG_LOGGING) Log.d(TAG, "revokeChallenge")
            mChallengeCount -= 1
            if (mChallengeCount <= 0 && mChallenge != 0L) {
                mChallenge = 0
                mChallengeCount = 0
                mWorkHandler!!.removeMessages(MSG_CHALLENGE_TIMEOUT)
                stopCurrentWork()
            }
            return 0
        }

        override fun getAuthenticatorId(): Int {
            activateUser()
            return -1
        }

        override fun resetLockout(cryptoToken: ByteArray?) {
            activateUser()
            resetLockoutCount()
        }
    }

    private fun sendLockoutError() {
        var errorCode = 0
        when (mLockoutType) {
            LOCKOUT_TYPE_PERMANENT -> errorCode =
                BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT
            LOCKOUT_TYPE_TIMED -> errorCode = BiometricFaceConstants.FACE_ERROR_LOCKOUT
            LOCKOUT_TYPE_IDLE -> errorCode = BiometricFaceConstants.FACE_ERROR_VENDOR
        }
        try {
            mSenseReceiver?.onError(errorCode, 0)
        } catch (e2: RemoteException) {
            e2.printStackTrace()
        }
    }

    private inner class FaceHandler(looper: Looper?) : Handler(
        looper!!
    ) {
        override fun handleMessage(message: Message) {
            if (message.what == MSG_CHALLENGE_TIMEOUT) {
                mChallenge = 0
                mChallengeCount = 0
                stopCurrentWork()
            }
        }
    }

    companion object {
        private const val TAG = "SenseService"
        private const val ALARM_FAIL_TIMEOUT_LOCKOUT = "co.aospa.sense.ACTION_LOCKOUT_RESET"
        private const val ALARM_TIMEOUT_FREEZED = "co.aospa.sense.freezedtimeout"
        private const val DEFAULT_IDLE_TIMEOUT_MS = (3600000 * 4 // 4 hours
                ).toLong()
        private const val FAIL_LOCKOUT_TIMEOUT_MS: Long = 30000 // 30 seconds
        private const val MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 10
        private const val MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 5
        private const val MSG_CHALLENGE_TIMEOUT = 100
        private const val LOCKOUT_TYPE_DISABLED = 0
        private const val LOCKOUT_TYPE_TIMED = 1
        private const val LOCKOUT_TYPE_PERMANENT = 2
        private const val LOCKOUT_TYPE_IDLE = 3
    }
}
