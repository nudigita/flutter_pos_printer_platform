package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.sersoluciones.flutter_pos_printer_platform.R
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * USB printing on Android.
 *
 * Threading:
 *  - Main thread: method-channel calls ([selectDevice], [printBytes]) and the
 *    permission / detach broadcasts. It owns [mPendingJobs] and the selection.
 *  - [mPrintExecutor] (one thread): owns the open connection ([mUsbDevice],
 *    [mUsbDeviceConnection], [mUsbInterface], [mEndPoint]) and runs every
 *    transfer, one job at a time, in the order the jobs were accepted.
 *
 * Every job carries the printer that was selected when it was sent, so a job
 * can never land on another printer, and every job reports whether its bytes
 * actually reached the printer — the caller is answered only after the
 * transfer, not when the job is accepted.
 *
 * A job for a printer we don't hold permission for (runtime USB grants are
 * wiped on reboot and on re-plug) waits in [mPendingJobs] until the grant
 * lands. Jobs queue there in order; none is ever dropped in favour of a
 * later one.
 *
 * A job must start within [JOB_START_DEADLINE_MS] of being accepted — whether
 * it waited for a grant or behind a stalled transfer — or it fails unsent:
 * by then the app has stopped waiting and will retry, and the late copy would
 * print twice.
 */
class USBPrinterService private constructor(private var mHandler: Handler?) {
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null

    // Owned by mPrintExecutor. mUsbDevice is read from the main thread only to
    // recognise our printer in a detach broadcast.
    @Volatile
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null

    @Volatile
    var state: Int = STATE_USB_NONE

    // The printer the app last selected via connectPrinter; printBytes sends to
    // it. Main thread only.
    private var mSelectedVendorId: Int? = null
    private var mSelectedProductId: Int? = null

    // Jobs waiting for a USB permission grant, oldest first. Main thread only.
    private val mPendingJobs = ArrayDeque<PrintJob>()

    private val mMainHandler = Handler(Looper.getMainLooper())
    private val mPrintExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private class PrintJob(
        val vendorId: Int,
        val productId: Int,
        val bytes: ByteArray,
        /** Invoked exactly once, on the main thread. */
        val onDone: (Boolean) -> Unit,
    ) {
        val acceptedAt: Long = SystemClock.elapsedRealtime()

        fun isFor(device: UsbDevice) =
            device.vendorId == vendorId && device.productId == productId
    }

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val usbDevice: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
            when (intent.action) {
                ACTION_USB_PERMISSION -> onPermissionResult(
                    context,
                    usbDevice,
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                )
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(context, usbDevice)
            }
        }
    }

    private fun onPermissionResult(context: Context, device: UsbDevice, granted: Boolean) {
        // The startup pre-warm requests permission for every attached device, so
        // a result here may be for a touch controller or a card reader. Only jobs
        // waiting on this device care.
        val waiting = takePendingJobsFor(device)
        if (granted) {
            Log.i(LOG_TAG, "USB permission granted for ${device.deviceName} (vendor_id: ${device.vendorId}, product_id: ${device.productId}); ${waiting.size} job(s) waiting")
            // Also when nothing is waiting: a grant that lands after its job
            // gave up tells the app the printer is usable again, so it retries.
            if (waiting.isNotEmpty() || isSelected(device)) updateState(STATE_USB_CONNECTED)
            waiting.forEach { submit(it) }
        } else if (waiting.isNotEmpty()) {
            Log.e(LOG_TAG, "USB permission denied for ${device.deviceName}; failing ${waiting.size} print job(s)")
            Toast.makeText(context, mContext?.getString(R.string.user_refuse_perm) + ": ${device.deviceName}", Toast.LENGTH_LONG).show()
            updateState(STATE_USB_NONE)
            waiting.forEach { it.onDone(false) }
        } else {
            Log.v(LOG_TAG, "USB permission denied for non-printer device ${device.deviceName}; ignoring")
        }
    }

    private fun onDetached(context: Context, device: UsbDevice) {
        takePendingJobsFor(device).forEach { it.onDone(false) }
        // Other USB devices (scanner, caller-ID modem, touch controller) come and
        // go; only our printer's detach should drop the connection.
        if (mUsbDevice?.deviceName != device.deviceName) return
        Toast.makeText(context, mContext?.getString(R.string.device_off), Toast.LENGTH_LONG).show()
        mPrintExecutor.execute { closeConnection() }
        updateState(STATE_USB_NONE)
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
        // printed.
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

    /** Drops the open connection; the next job reopens it. */
    fun closeConnectionIfExists() {
        mPrintExecutor.execute { closeConnection() }
    }

    val deviceList: List<UsbDevice>
        get() {
            if (mUSBManager == null) {
                Toast.makeText(mContext, mContext?.getString(R.string.not_usb_manager), Toast.LENGTH_LONG).show()
                return emptyList()
            }
            return ArrayList(mUSBManager!!.deviceList.values)
        }

    /**
     * Makes (vendorId, productId) the target of the following [printBytes]
     * calls. Returns false when no such device is attached. Nothing is opened
     * here: the print thread opens the connection when a job needs it, and
     * permission is requested then if missing.
     */
    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        mSelectedVendorId = vendorId
        mSelectedProductId = productId
        return findDevice(vendorId, productId) != null
    }

    /**
     * Sends [bytes] to the selected printer. [onDone] is called once, on the
     * main thread, with whether every byte reached the printer.
     */
    fun printBytes(bytes: ByteArray, onDone: (Boolean) -> Unit) {
        val vendorId = mSelectedVendorId
        val productId = mSelectedProductId
        if (vendorId == null || productId == null) {
            Log.e(LOG_TAG, "printBytes called before connectPrinter selected a printer")
            onDone(false)
            return
        }
        Log.v(LOG_TAG, "Printing ${bytes.size} bytes to USB $vendorId:$productId")
        enqueue(PrintJob(vendorId, productId, bytes, onDone))
    }

    private fun enqueue(job: PrintJob) {
        val manager = mUSBManager
        val device = findDevice(job.vendorId, job.productId)
        if (manager == null || device == null) {
            Log.e(LOG_TAG, "USB printer ${job.vendorId}:${job.productId} is not attached")
            job.onDone(false)
            return
        }

        // Jobs for a printer print in the order they were sent: if earlier ones
        // are still waiting on its grant, this one waits behind them.
        val waitingAhead = mPendingJobs.any { it.isFor(device) }
        if (manager.hasPermission(device) && !waitingAhead) {
            submit(job)
            return
        }

        Log.i(LOG_TAG, "USB permission pending for ${device.deviceName}; holding print job until the grant lands")
        mPendingJobs.addLast(job)
        if (!waitingAhead) {
            manager.requestPermission(device, mPermissionIndent)
            updateState(STATE_USB_CONNECTING)
        }
        // Don't hold a job indefinitely (e.g. a permission dialog nobody
        // answers): fail it so the caller can retry once the grant is in place.
        mMainHandler.postDelayed({
            if (mPendingJobs.remove(job)) {
                Log.e(LOG_TAG, "USB permission for ${device.deviceName} not granted within ${JOB_START_DEADLINE_MS}ms; failing print job")
                job.onDone(false)
            }
        }, JOB_START_DEADLINE_MS)
    }

    private fun isSelected(device: UsbDevice) =
        device.vendorId == mSelectedVendorId && device.productId == mSelectedProductId

    private fun takePendingJobsFor(device: UsbDevice): List<PrintJob> {
        val jobs = mPendingJobs.filter { it.isFor(device) }
        mPendingJobs.removeAll(jobs)
        return jobs
    }

    private fun submit(job: PrintJob) {
        mPrintExecutor.execute {
            val ok = try {
                runJob(job)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "USB print job failed", e)
                closeConnection()
                false
            }
            mMainHandler.post { job.onDone(ok) }
        }
    }

    // ── Print thread only ────────────────────────────────────────────────

    private fun runJob(job: PrintJob): Boolean {
        val waited = SystemClock.elapsedRealtime() - job.acceptedAt
        if (waited > JOB_START_DEADLINE_MS) {
            Log.e(LOG_TAG, "USB print job waited ${waited}ms to start; failing it unsent")
            return false
        }
        val device = findDevice(job.vendorId, job.productId)
        if (device == null) {
            Log.e(LOG_TAG, "USB printer ${job.vendorId}:${job.productId} detached before printing")
            return false
        }
        if (mUSBManager?.hasPermission(device) != true) {
            Log.e(LOG_TAG, "No USB permission for ${device.deviceName}")
            return false
        }
        if (!ensureConnection(device)) return false
        val ok = transfer(job.bytes)
        // A failed transfer leaves the endpoint in an unknown state: reopen next time.
        if (!ok) closeConnection()
        return ok
    }

    private fun ensureConnection(device: UsbDevice): Boolean {
        if (mUsbDeviceConnection != null && mUsbDevice?.deviceName == device.deviceName) return true
        closeConnection()

        val manager = mUSBManager ?: return false
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (e in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK || ep.direction != UsbConstants.USB_DIR_OUT) continue

                val connection = manager.openDevice(device)
                if (connection == null) {
                    Log.e(LOG_TAG, "Failed to open USB connection to ${device.deviceName}")
                    return false
                }
                if (!connection.claimInterface(usbInterface, true)) {
                    connection.close()
                    Log.e(LOG_TAG, "Failed to claim USB interface $i on ${device.deviceName}")
                    return false
                }
                mUsbDevice = device
                mUsbDeviceConnection = connection
                mUsbInterface = usbInterface
                mEndPoint = ep
                updateState(STATE_USB_CONNECTED)
                mMainHandler.post {
                    Toast.makeText(mContext, mContext?.getString(R.string.connected_device), Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        Log.e(LOG_TAG, "No bulk OUT endpoint on ${device.deviceName}")
        return false
    }

    private fun transfer(bytes: ByteArray): Boolean {
        val connection = mUsbDeviceConnection ?: return false
        val endpoint = mEndPoint ?: return false
        val chunkSize = endpoint.maxPacketSize.coerceAtLeast(1)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(chunkSize, bytes.size - offset)
            val chunk = Arrays.copyOfRange(bytes, offset, offset + length)
            val sent = connection.bulkTransfer(endpoint, chunk, length, CHUNK_TIMEOUT_MS)
            if (sent < 0) {
                Log.e(LOG_TAG, "USB transfer failed at byte $offset of ${bytes.size}: bulkTransfer returned $sent")
                return false
            }
            offset += length
        }
        Log.i(LOG_TAG, "USB transfer complete: ${bytes.size} bytes")
        return true
    }

    private fun closeConnection() {
        val connection = mUsbDeviceConnection
        if (connection != null) {
            try {
                mUsbInterface?.let { connection.releaseInterface(it) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "releaseInterface failed: ${e.message}")
            }
            connection.close()
        }
        mUsbDeviceConnection = null
        mUsbInterface = null
        mEndPoint = null
        mUsbDevice = null
    }

    // ── Either thread ────────────────────────────────────────────────────

    private fun findDevice(vendorId: Int, productId: Int): UsbDevice? =
        mUSBManager?.deviceList?.values?.firstOrNull {
            it.vendorId == vendorId && it.productId == productId
        }

    private fun updateState(newState: Int) {
        state = newState
        mHandler?.obtainMessage(newState)?.sendToTarget()
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var mInstance: USBPrinterService? = null
        private const val LOG_TAG = "ESC POS Printer"
        private const val ACTION_USB_PERMISSION = "com.flutter_pos_printer.USB_PERMISSION"

        // Per-chunk bulkTransfer timeout, unchanged from the synchronous version.
        private const val CHUNK_TIMEOUT_MS = 100_000

        // Shorter than the app's USB send timeout (10 s + size allowance), so the
        // app hears "failed" rather than giving up while the job still waits
        // here — a job the app has given up on must never print later.
        private const val JOB_START_DEADLINE_MS = 8_000L

        // Constants that indicate the current connection state
        const val STATE_USB_NONE = 0 // we're doing nothing
        const val STATE_USB_CONNECTING = 2 // now initiating an outgoing connection
        const val STATE_USB_CONNECTED = 3 // now connected to a remote device

        fun getInstance(handler: Handler): USBPrinterService {
            if (mInstance == null) {
                mInstance = USBPrinterService(handler)
            }
            return mInstance!!
        }
    }
}
