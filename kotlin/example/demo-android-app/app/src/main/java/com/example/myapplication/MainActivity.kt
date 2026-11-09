package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback
import com.google.zxing.BarcodeFormat
import androidx.lifecycle.lifecycleScope
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import com.google.zxing.qrcode.QRCodeWriter
import io.mosip.tuvali.common.events.SecureChannelEstablishedEvent
import io.mosip.tuvali.common.events.DataReceivedEvent
import io.mosip.tuvali.iso.MdocCallbacks
import io.mosip.tuvali.iso.MdocPresentationOptions
import io.mosip.tuvali.iso.MdocPresenter
import io.mosip.tuvali.iso.MdocProgress
import io.mosip.tuvali.iso.MdocRequest
import io.mosip.tuvali.iso.MdocResult
import io.mosip.tuvali.iso.MdocSharingDecision
import io.mosip.tuvali.iso.ReaderTrustStatus
import io.mosip.tuvali.verifier.Verifier
import io.mosip.tuvali.wallet.Wallet
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), OnRequestPermissionsResultCallback {

  private lateinit var wallet: Wallet
  private lateinit var verifier: Verifier
  private lateinit var userInput: EditText
  private lateinit var sendButton: Button

  private val TAG = "TuvaliDemo"

  private var sendStartTime = 0L

  @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    wallet = Wallet(this)
    verifier = Verifier(this)
    Common.requestForRequiredPermissions(this, this, this::showActionsView)
  }

  @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (grantResults.any { it != 0 }) {
      showPermErrorView()
      return
    }
    Common.requestForRequiredPermissions(this, this, this::showActionsView)
  }

  private fun showActionsView() {
    setContentView(R.layout.activity_main)

    findViewById<Button>(R.id.walletButton)?.setOnClickListener {
      val intent = Intent(this, QrScanActivity::class.java)
      startActivityForResult(intent, 101)
    }

    findViewById<Button>(R.id.verifierButton)?.setOnClickListener {
      startAdvertising()
    }

    findViewById<Button>(R.id.mdocButton)?.setOnClickListener {
      startMdocPresentation()
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == 101 && resultCode == RESULT_OK) {
      val uri = data?.getStringExtra("SCANNED_URI")
      if (!uri.isNullOrEmpty()) {
        startScanningWithUri(uri)
      }
    }
  }

  @SuppressLint("MissingInflatedId")
  private fun startScanningWithUri(uri: String) {
    showLoadingLayout()
    updateLoadingText(getString(R.string.ScanningMessage))
    setCancelLoadingButton { stopScanning() }

    findViewById<LinearLayout>(R.id.qrSection)?.visibility = View.GONE

    wallet.startConnection(uri)
    wallet.subscribe { event ->

      when (event) {

        is SecureChannelEstablishedEvent -> {
          runOnUiThread {
            setContentView(R.layout.activity_send_input)
            userInput = findViewById(R.id.userInput)
            sendButton = findViewById(R.id.sendButton)

            sendButton.setOnClickListener {
              val msg = userInput.text.toString()
              if (msg.isNotEmpty()) {

                sendStartTime = System.currentTimeMillis()
                Log.d(TAG, "Timer START at $sendStartTime")

                val payload = "$sendStartTime|$msg"
                wallet.sendData(payload)
              }
            }

            findViewById<Button>(R.id.disconnectButton)?.setOnClickListener {
              stopScanning()
            }
          }
        }

        is DataReceivedEvent -> {
          logPayload("Wallet received", event.data)
        }
      }
    }
  }

  private fun stopScanning() {
    wallet.disconnect()
    showActionsView()
  }

  @SuppressLint("SetTextI18n")
  private fun startAdvertising() {
    val uri = verifier.startAdvertisement("OVPMOSIP")
    updateQRCodeData(uri)
    showQRCode(uri)

    findViewById<LinearLayout>(R.id.qrSection)?.visibility = View.VISIBLE

    showLoadingLayout()
    updateLoadingText("Broadcasting…")
    setCancelLoadingButton { stopAdvertisement() }

    verifier.subscribe { event ->

      when (event) {

        is SecureChannelEstablishedEvent -> {
          runOnUiThread {
            findViewById<TextView>(R.id.transferText)?.text =
              "Secure channel established.\nWaiting for data…"
          }
        }

        is DataReceivedEvent -> {
          val raw = payloadToDisplay(event.data)

          try {
            val parts = raw.split("|", limit = 2)
            val sentAt = parts[0].toLong()
            val actualMsg = parts.getOrNull(1) ?: ""

            val receivedAt = System.currentTimeMillis()
            val transferTime = receivedAt - sentAt

            Log.d(TAG, "Verifier received TEXT=$actualMsg")
            Log.d(TAG, "Transfer time = $transferTime ms")

            runOnUiThread {
              findViewById<TextView>(R.id.transferText)?.text =
                "Transfer Time: $transferTime ms\n\nMessage: $actualMsg"
            }

          } catch (ex: Exception) {
            Log.e(TAG, "Failed to parse timestamp from payload: $raw", ex)
            runOnUiThread {
              findViewById<TextView>(R.id.transferText)?.text =
                "Received (no timestamp):\n$raw"
            }
          }
        }
      }
    }
  }

  private fun stopAdvertisement() {
    verifier.disconnect()
    showActionsView()
  }

  // ============================================================
  // MDOC HOLDER SIDE (ISO 18013-5, wallet as BLE client)
  // ============================================================

  private val presenter by lazy { MdocPresenter(this) }

  /** Logs and also appends to files/mdoc-runs.log, so results survive a dropped USB link. */
  private fun mdocLog(message: String) {
    Log.i(TAG, "mdoc $message")
    runCatching { java.io.File(filesDir, "mdoc-runs.log").appendText("${java.util.Date()} $message\n") }
  }

  private fun startMdocPresentation() {
    showLoadingLayout()
    findViewById<LinearLayout>(R.id.qrSection)?.visibility = View.VISIBLE
    findViewById<TextView>(R.id.transferText)?.text = ""
    findViewById<ImageView>(R.id.qrCodeImage)?.visibility = View.VISIBLE
    setCancelLoadingButton { presenter.cancel() }

    lifecycleScope.launch {
      val result = presenter.present(MdocPresentationOptions(), mdocCallbacks)
      mdocLog("presentation ended: ${describe(result)}")
      showActionsView()
      findViewById<LinearLayout>(R.id.qrSection)?.visibility = View.VISIBLE
      findViewById<ImageView>(R.id.qrCodeImage)?.visibility = View.GONE
      updateQRCodeData("")
      findViewById<TextView>(R.id.transferText)?.text = "Last mdoc result:\n${describe(result)}"
    }
  }

  private val mdocCallbacks = object : MdocCallbacks {
    override suspend fun onQrReady(qrText: String) {
      mdocLog("QR ready")
      updateQRCodeData(qrText)
      showQRCode(qrText)
    }

    override fun onProgress(progress: MdocProgress) {
      mdocLog("progress: $progress")
      updateLoadingText(progress.name)
    }

    override suspend fun onRequest(request: MdocRequest): MdocSharingDecision {
      val summary = request.documents.joinToString("\n\n") { doc ->
        "${doc.docType}\nreader: ${describe(doc.readerTrust)}\n" +
          doc.nameSpaces.entries.joinToString("\n") { (nameSpace, elements) ->
            "$nameSpace: ${elements.keys.joinToString()}"
          }
      }
      mdocLog("request #${request.requestNumber}:\n$summary")
      findViewById<TextView>(R.id.transferText)?.text = "Request #${request.requestNumber}\n\n$summary"
      return askHolder(emptyDeviceResponse(request.documents.map { it.docType }))
    }
  }

  private suspend fun askHolder(answer: ByteArray): MdocSharingDecision {
    val decision = CompletableDeferred<MdocSharingDecision>()
    val buttons = findViewById<LinearLayout>(R.id.mdocDecisionLayout)
    buttons?.visibility = View.VISIBLE
    findViewById<ScrollView>(R.id.mainScroll)?.let { scroll -> scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    findViewById<Button>(R.id.mdocSendBtn)?.setOnClickListener {
      decision.complete(MdocSharingDecision.Send(answer))
    }
    findViewById<Button>(R.id.mdocDeclineBtn)?.setOnClickListener {
      decision.complete(MdocSharingDecision.Decline)
    }
    return try {
      decision.await()
    } finally {
      buttons?.visibility = View.GONE
    }
  }

  /**
   * A valid DeviceResponse that shares nothing (ISO 18013-5 8.3.2.1.2.2): status 0, no documents,
   * and error 0 ("data not returned") for each requested docType. Enough to test the session.
   */
  private fun emptyDeviceResponse(docTypes: List<String>): ByteArray {
    val documentErrors = CborArray()
    docTypes.distinct().forEach { docType ->
      documentErrors.add(CborMap().put(UnicodeString(docType), UnsignedInteger(0)))
    }
    val response = CborMap()
      .put(UnicodeString("version"), UnicodeString("1.0"))
      .put(UnicodeString("documentErrors"), documentErrors)
      .put(UnicodeString("status"), UnsignedInteger(0))
    return ByteArrayOutputStream().also { CborEncoder(it).encode(response) }.toByteArray()
  }

  private fun describe(trust: ReaderTrustStatus): String = when (trust) {
    ReaderTrustStatus.NotSigned -> "not signed"
    is ReaderTrustStatus.Verified -> "verified (${trust.readerName})"
    is ReaderTrustStatus.Unverified -> "unverified (${trust.readerName}): ${trust.reason}"
    is ReaderTrustStatus.Invalid -> "INVALID: ${trust.reason}"
  }

  private fun describe(result: MdocResult): String = when (result) {
    is MdocResult.Completed -> "Completed (reader confirmed: ${result.confirmedByReader})"
    MdocResult.Declined -> "Declined"
    MdocResult.Cancelled -> "Cancelled"
    is MdocResult.Failed -> "Failed: ${result.failure} ${result.detail.orEmpty()}"
  }

  private fun setCancelLoadingButton(onClick: (View) -> Unit) {
    findViewById<Button>(R.id.cancelLoadingBtn)?.setOnClickListener(onClick)
  }

  private fun showLoadingLayout() {
    findViewById<LinearLayout>(R.id.actionsLayout)?.visibility = View.GONE
    findViewById<LinearLayout>(R.id.loaderLayout)?.visibility = View.VISIBLE
  }

  private fun showPermErrorView() {
    setContentView(R.layout.activity_main_error)
    findViewById<TextView>(R.id.errorText)?.text = getString(R.string.permission_error_message)
    findViewById<Button>(R.id.requestPermBtn)?.setOnClickListener {
      Common.requestForRequiredPermissions(this, this, this::showActionsView)
    }
  }

  private fun updateLoadingText(msg: String) {
    findViewById<TextView>(R.id.loadingText)?.text = msg
  }

  private fun updateQRCodeData(msg: String) {
    findViewById<TextView>(R.id.qrCodeData)?.text = msg
  }

  private fun showQRCode(uri: String) {
    val size = 512
    try {
      val bitMatrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size)
      val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
      for (x in 0 until size) {
        for (y in 0 until size) {
          bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
        }
      }
      findViewById<ImageView>(R.id.qrCodeImage)?.setImageBitmap(bmp)
    } catch (e: Exception) {
      Log.e(TAG, "QR generation error: ${e.message}")
    }
  }

  private fun logPayload(prefix: String, data: Any?) {
    when (data) {
      is ByteArray -> Log.d(TAG, "$prefix (bytes=${data.size})")
      is String -> Log.d(TAG, "$prefix TEXT=$data")
      else -> Log.d(TAG, "$prefix UNKNOWN=$data")
    }
  }

  private fun payloadToDisplay(data: Any?): String {
    return when (data) {
      is ByteArray -> kotlin.runCatching { String(data, Charsets.UTF_8) }
        .getOrDefault(data.joinToString(" ") { "%02X".format(it) })

      is String -> data
      else -> data.toString()
    }
  }

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    val v = currentFocus
    if (v is EditText) {
      val scr = IntArray(2)
      v.getLocationOnScreen(scr)
      val x = ev.rawX + v.left - scr[0]
      val y = ev.rawY + v.top - scr[1]

      if (ev.action == MotionEvent.ACTION_DOWN &&
        (x < v.left || x >= v.right || y < v.top || y > v.bottom)
      ) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
      }
    }
    return super.dispatchTouchEvent(ev)
  }
}
