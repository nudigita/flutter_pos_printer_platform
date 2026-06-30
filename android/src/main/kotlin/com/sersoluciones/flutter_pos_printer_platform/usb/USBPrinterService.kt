package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.sersoluciones.flutter_pos_printer_platform.R
import java.nio.charset.Charset
import java.util.*

class USBPrinterService private constructor(private var mHandler: Handler?) {
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null
    var state: Int = STATE_USB_NONE

    // A print job that arrived before USB permission was granted. Runtime USB
    // permissions are wiped on every reboot, so the first job after boot races
    // ahead of the async grant. We stash it here and run it from the
    // ACTION_USB_PERMISSION receiver once the grant actually lands.
    private var mPendingPrintBytes: ArrayList<Int>? = null

    // The vendor/product the app last asked us to print to (via selectDevice).
    // We only adopt a permission grant as the active print target when it matches
    // this — so the startup pre-warm, which requests permission for *every*
    // attached USB device, never hijacks mUsbDevice with a non-printer device.
    private var mSelectedVendorId: Int? = null
    private var mSelectedProductId: Int? = null

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if ((ACTION_USB_PERMISSION == action)) {
                synchronized(this) {
                    val usbDevice: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    // Only react to the device the app actually selected. The startup
                    // pre-warm requests permission for every attached USB device, so a
                    // grant here may be for an unrelated device (touch controller, hub,
                    // card reader). Those must not become the active print target.
                    val isSelectedTarget = usbDevice != null &&
                        usbDevice.vendorId == mSelectedVendorId &&
                        usbDevice.productId == mSelectedProductId
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(
                            LOG_TAG,
                            "Success get permission for device ${usbDevice?.deviceId}, vendor_id: ${usbDevice?.vendorId} product_id: ${usbDevice?.productId}"
                        )
                        if (isSelectedTarget) {
                            mUsbDevice = usbDevice
                            state = STATE_USB_CONNECTED
                            mHandler?.obtainMessage(STATE_USB_CONNECTED)?.sendToTarget()

                            // The grant has landed. If a print job was deferred while
                            // waiting for it, run it now — this is the missing link that
                            // made the first print after every reboot fail. onReceive runs
                            // on the main thread, the same thread printBytes() runs on, so
                            // invoking it here preserves the existing synchronous behaviour.
                            val pending = mPendingPrintBytes
                            if (pending != null) {
                                mPendingPrintBytes = null
                                Log.i(LOG_TAG, "Running deferred USB print job after permission grant")
                                val printed = doPrintBytes(pending)
                                Log.i(LOG_TAG, "Deferred USB print job completed: $printed")
                            } else {
                                Log.v(LOG_TAG, "Permission granted; no deferred USB print job pending")
                            }
                        } else {
                            // Pre-warm grant for a non-target device: permission is now
                            // cached at OS level, but it isn't our printer — leave the
                            // active device untouched.
                            Log.v(LOG_TAG, "USB permission cached for non-target device; ignoring")
                        }
                    } else if (isSelectedTarget) {
                        // Our printer's permission was denied: surface a clear error and
                        // drop the deferred job rather than letting it hang or silently
                        // disappear. (Denials for non-target pre-warm devices are ignored.)
                        mPendingPrintBytes = null
                        Log.e(LOG_TAG, "USB permission denied for device ${usbDevice?.deviceName}; dropping pending print job")
                        Toast.makeText(context, mContext?.getString(R.string.user_refuse_perm) + ": ${usbDevice!!.deviceName}", Toast.LENGTH_LONG).show()
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                    } else {
                        Log.v(LOG_TAG, "USB permission denied for non-target device ${usbDevice?.deviceName}; ignoring")
                    }
                }
            } else if ((UsbManager.ACTION_USB_DEVICE_DETACHED == action)) {

                if (mUsbDevice != null) {
                    Toast.makeText(context, mContext?.getString(R.string.device_off), Toast.LENGTH_LONG).show()
                    closeConnectionIfExists()
                    state = STATE_USB_NONE
                    mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                }

            } else if ((UsbManager.ACTION_USB_DEVICE_ATTACHED == action)) {
//                if (mUsbDevice != null) {
//                    Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG).show()
//                    closeConnectionIfExists()
//                }
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        mPermissionIndent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            // Android 14 (API 34)+ requires explicit intent with FLAG_MUTABLE
            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION).apply {
                setPackage(mContext!!.packageName)
            }, PendingIntent.FLAG_MUTABLE)
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE)
        } else {
            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), 0)
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        // Android 14+ requires specifying receiver export behavior
        // RECEIVER_NOT_EXPORTED = 4 (Context.RECEIVER_NOT_EXPORTED)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            mContext!!.registerReceiver(mUsbDeviceReceiver, filter, 4)
        } else {
            mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        }
        Log.v(LOG_TAG, "ESC/POS Printer initialized")

        // Belt-and-braces: pre-request permission for already-attached USB devices
        // at startup. Runtime USB permissions are cleared on every reboot, so doing
        // this here lets the grant land and cache well before the first receipt is
        // printed — making even a one-shot print path immune to the boot-time race.
        try {
            for (device in ArrayList(mUSBManager!!.deviceList.values)) {
                if (!mUSBManager!!.hasPermission(device)) {
                    Log.v(LOG_TAG, "Pre-warming USB permission for device ${device.deviceName} (vendor_id: ${device.vendorId}, product_id: ${device.productId})")
                    mUSBManager!!.requestPermission(device, mPermissionIndent)
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "USB permission pre-warm failed: ${e.message}")
        }
    }

    fun closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection!!.releaseInterface(mUsbInterface)
            mUsbDeviceConnection!!.close()
            mUsbInterface = null
            mEndPoint = null
            mUsbDevice = null
            mUsbDeviceConnection = null
        }
    }

    val deviceList: List<UsbDevice>
        get() {
            if (mUSBManager == null) {
                Toast.makeText(mContext, mContext?.getString(R.string.not_usb_manager), Toast.LENGTH_LONG).show()
                return emptyList()
            }
            return ArrayList(mUSBManager!!.deviceList.values)
        }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
//        Log.v(LOG_TAG, " status usb ______ $state")
        mSelectedVendorId = vendorId
        mSelectedProductId = productId
        if ((mUsbDevice == null) || (mUsbDevice!!.vendorId != vendorId) || (mUsbDevice!!.productId != productId)) {
            synchronized(printLock) {
                closeConnectionIfExists()
                val usbDevices: List<UsbDevice> = deviceList
                for (usbDevice: UsbDevice in usbDevices) {
                    if ((usbDevice.vendorId == vendorId) && (usbDevice.productId == productId)) {
                        Log.v(LOG_TAG, "Request for device: vendor_id: " + usbDevice.vendorId + ", product_id: " + usbDevice.productId)
                        closeConnectionIfExists()
                        mUSBManager!!.requestPermission(usbDevice, mPermissionIndent)
                        state = STATE_USB_CONNECTING
                        mHandler?.obtainMessage(STATE_USB_CONNECTING)?.sendToTarget()
                        return true
                    }
                }
                return false
            }
        } else {
            mHandler?.obtainMessage(state)?.sendToTarget()
        }

        return true
    }

    private fun openConnection(): Boolean {
        if (mUsbDevice == null) {
            Log.e(LOG_TAG, "USB Device is not initialized")
            return false
        }
        if (mUSBManager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized")
            return false
        }
        if (mUsbDeviceConnection != null) {
            Log.i(LOG_TAG, "USB Connection already connected")
            return true
        }
        val usbInterface = mUsbDevice!!.getInterface(0)
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    val usbDeviceConnection = mUSBManager!!.openDevice(mUsbDevice)
                    if (usbDeviceConnection == null) {
                        Log.e(LOG_TAG, "Failed to open USB Connection")
                        return false
                    }
                    Toast.makeText(mContext, mContext?.getString(R.string.connected_device), Toast.LENGTH_SHORT).show()
                    return if (usbDeviceConnection.claimInterface(usbInterface, true)) {
                        mEndPoint = ep
                        mUsbInterface = usbInterface
                        mUsbDeviceConnection = usbDeviceConnection
                        true
                    } else {
                        usbDeviceConnection.close()
                        Log.e(LOG_TAG, "Failed to retrieve usb connection")
                        false
                    }
                }
            }
        }
        return true
    }

    fun printText(text: String): Boolean {
        Log.v(LOG_TAG, "Printing text")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                synchronized(printLock) {
                    val bytes: ByteArray = text.toByteArray(Charset.forName("UTF-8"))
                    val b: Int = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Return code: $b")
                }
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "Failed to connect to device")
            false
        }
    }

    fun printRawData(data: String): Boolean {
        Log.v(LOG_TAG, "Printing raw data: $data")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                synchronized(printLock) {
                    val bytes: ByteArray = Base64.decode(data, Base64.DEFAULT)
                    val b: Int = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, bytes, bytes.size, 100000)
                    Log.i(LOG_TAG, "Return code: $b")
                }
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "Failed to connected to device")
            false
        }
    }

    fun printBytes(bytes: ArrayList<Int>): Boolean {
        Log.v(LOG_TAG, "Printing ${bytes.size} bytes to USB")

        // Gate on permission. On a freshly booted device the runtime USB grant is
        // gone, so selectDevice() has only *requested* it and mUsbDevice is still
        // null / unpermitted. Opening + sending now would hit the async grant race
        // and fail ("USB Device is not initialized"). Instead, stash the job and let
        // the ACTION_USB_PERMISSION receiver run it once the grant lands.
        //
        // Require that mUsbDevice is the device selectDevice() just chose AND that we
        // hold permission for it — so a stale device (e.g. after switching between two
        // USB printers) is never printed to, and a missing grant always defers.
        val device = mUsbDevice
        val targetReady = device != null &&
            device.vendorId == mSelectedVendorId &&
            device.productId == mSelectedProductId &&
            mUSBManager?.hasPermission(device) == true
        if (!targetReady) {
            Log.i(LOG_TAG, "USB target not ready (permission pending); deferring print job until grant lands")
            mPendingPrintBytes = bytes
            // Re-request only when the active device IS the selected one but its grant
            // is missing (e.g. revoked). For a null/mismatched device, selectDevice()
            // has already requested the correct device — don't poke a stale one.
            if (device != null &&
                device.vendorId == mSelectedVendorId &&
                device.productId == mSelectedProductId) {
                mUSBManager?.requestPermission(device, mPermissionIndent)
            }
            // selectDevice() (called by connectPrinter, before this) has already
            // fired requestPermission for the selected device, so the grant is on its
            // way regardless. Report accepted; the receiver runs the job on grant.
            return true
        }
        return doPrintBytes(bytes)
    }

    private fun doPrintBytes(bytes: ArrayList<Int>): Boolean {
        val isConnected = openConnection()
        if (isConnected) {
            val chunkSize = mEndPoint!!.maxPacketSize
            Log.v(LOG_TAG, "USB endpoint max packet size: $chunkSize")

            // FIXED: Run synchronously instead of in background thread
            synchronized(printLock) {
                val vectorData: Vector<Byte> = Vector()
                for (i in bytes.indices) {
                    val `val`: Int = bytes[i]
                    vectorData.add(`val`.toByte())
                }
                val temp: Array<Any> = vectorData.toTypedArray()
                val byteData = ByteArray(temp.size)
                for (i in temp.indices) {
                    byteData[i] = temp[i] as Byte
                }

                if (mUsbDeviceConnection != null) {
                    var success = true
                    if (byteData.size > chunkSize) {
                        var chunks: Int = byteData.size / chunkSize
                        if (byteData.size % chunkSize > 0) {
                            ++chunks
                        }
                        Log.v(LOG_TAG, "Sending data in $chunks chunks")
                        for (i in 0 until chunks) {
                            val buffer: ByteArray = Arrays.copyOfRange(byteData, i * chunkSize, minOf(chunkSize + i * chunkSize, byteData.size))
                            val b = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, buffer, buffer.size, 100000)
                            Log.i(LOG_TAG, "Chunk $i return code: $b")
                            if (b < 0) {
                                Log.e(LOG_TAG, "USB transfer failed for chunk $i with error code: $b")
                                success = false
                                break
                            }
                        }
                    } else {
                        val b = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, byteData, byteData.size, 100000)
                        Log.i(LOG_TAG, "USB bulkTransfer return code: $b (expected ${byteData.size})")
                        if (b < 0) {
                            Log.e(LOG_TAG, "USB transfer failed with error code: $b")
                            success = false
                        }
                    }
                    return success
                } else {
                    Log.e(LOG_TAG, "USB connection is null!")
                    return false
                }
            }
        } else {
            Log.v(LOG_TAG, "Failed to connected to device")
            return false
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var mInstance: USBPrinterService? = null
        private const val LOG_TAG = "ESC POS Printer"
        private const val ACTION_USB_PERMISSION = "com.flutter_pos_printer.USB_PERMISSION"

        // Constants that indicate the current connection state
        const val STATE_USB_NONE = 0 // we're doing nothing
        const val STATE_USB_CONNECTING = 2 // now initiating an outgoing connection
        const val STATE_USB_CONNECTED = 3 // now connected to a remote device

        private val printLock = Any()

        fun getInstance(handler: Handler): USBPrinterService {
            if (mInstance == null) {
                mInstance = USBPrinterService(handler)
            }
            return mInstance!!
        }
    }
}