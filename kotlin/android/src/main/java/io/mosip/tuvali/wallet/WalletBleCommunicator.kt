package io.mosip.tuvali.wallet

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.Process
import android.util.Log
import io.mosip.tuvali.ble.central.Central
import io.mosip.tuvali.ble.central.ICentralListener
import io.mosip.tuvali.common.Utils
import io.mosip.tuvali.common.advertisementPayload.AdvertisementPayload
import io.mosip.tuvali.common.retrymechanism.BackOffStrategy
import io.mosip.tuvali.cryptography.SecretsTranslator
import io.mosip.tuvali.cryptography.WalletCryptoBox
import io.mosip.tuvali.cryptography.WalletCryptoBoxBuilder
import io.mosip.tuvali.exception.BLEException
import io.mosip.tuvali.common.events.VerificationStatusEvent
import io.mosip.tuvali.common.events.ConnectedEvent
import io.mosip.tuvali.common.events.DataSentEvent
import io.mosip.tuvali.common.events.DisconnectedEvent
import io.mosip.tuvali.common.events.SecureChannelEstablishedEvent
import io.mosip.tuvali.common.events.EventEmitter
import io.mosip.tuvali.transfer.TransferReport
import io.mosip.tuvali.transfer.Util
import io.mosip.tuvali.transfer.Util.Companion.getLogTag
import io.mosip.tuvali.verifier.GattService
import io.mosip.tuvali.verifier.VerifierBleCommunicator
import io.mosip.tuvali.verifier.VerifierBleCommunicator.Companion.DISCONNECT_STATUS
import io.mosip.tuvali.wallet.exception.MTUNegotiationFailedException
import io.mosip.tuvali.wallet.exception.ServiceNotFoundException
import io.mosip.tuvali.wallet.exception.TransferFailedException
import io.mosip.tuvali.wallet.exception.WalletException
import io.mosip.tuvali.wallet.transfer.ITransferListener
import io.mosip.tuvali.wallet.transfer.TransferHandler
import io.mosip.tuvali.wallet.transfer.message.*
import org.bouncycastle.util.encoders.Hex
import java.security.SecureRandom
import java.util.*

private const val MTU_REQUEST_RETRY_DELAY_TIME_IN_MILLIS = 500L

class WalletBleCommunicator(context: Context, private val eventEmitter: EventEmitter, private val handleException: (BLEException) -> Unit) : ICentralListener, ITransferListener {
  private val logTag = getLogTag(javaClass.simpleName)

  private val secureRandom: SecureRandom = SecureRandom(Utils.longToBytes(System.nanoTime()))
  private lateinit var verifierPK: ByteArray
  private var walletCryptoBox: WalletCryptoBox = WalletCryptoBoxBuilder.build(secureRandom)
  private var secretsTranslator: SecretsTranslator? = null

  private var advPayload: ByteArray? = null
  private var advName: String? = null
  private var qrVerifierPK: ByteArray? = null
  private var transferHandler: TransferHandler
  private val handlerThread = HandlerThread("TransferHandlerThread", Process.THREAD_PRIORITY_DEFAULT)

  private var central: Central

  //default mtu is 23 bytes and the allowed data bytes is 20 bytes
  private var maxDataBytes = 20
  private val mtuValues = arrayOf(512, 185, 100)

  private val retryDiscoverServices = BackOffStrategy(maxRetryLimit = 5)

  private enum class CentralCallbacks {
    ON_DESTROY_SUCCESS_CALLBACK
  }

  private val callbacks = mutableMapOf<CentralCallbacks, () -> Unit>()

  private val connectionMutex = Object()

  @Volatile
  private var connectionState = VerifierConnectionState.NOT_CONNECTED

  private enum class VerifierConnectionState {
    NOT_CONNECTED, CONNECTING, CONNECTED
  }
  init {
    central = Central(context, this@WalletBleCommunicator)
    handlerThread.start()
    transferHandler = TransferHandler(handlerThread.looper, central, VerifierBleCommunicator.SERVICE_UUID, this@WalletBleCommunicator)
  }

  fun stop(onDestroy: () -> Unit) {
    callbacks[CentralCallbacks.ON_DESTROY_SUCCESS_CALLBACK] = onDestroy
    central.stop()
    handlerThread.quitSafely()
  }

  fun startScanning() {
    central.scan(VerifierBleCommunicator.SERVICE_UUID)
  }

  fun writeToIdentifyRequest() {
    val publicKey = walletCryptoBox.publicKey()
    secretsTranslator = walletCryptoBox.buildSecretsTranslator(verifierPK)
    val nonce = secretsTranslator?.nonce
    central.write(VerifierBleCommunicator.SERVICE_UUID, GattService.IDENTIFY_REQUEST_CHAR_UUID, nonce!! + publicKey!!)
    Log.d(logTag, "Started to write - generated nonce ${
      Hex.toHexString(nonce)
    }, Public Key of wallet: ${Hex.toHexString(publicKey)}")
  }

  override fun onScanStartedFailed(errorCode: Int) {
    Log.d(logTag, "onScanStartedFailed: $errorCode")
    //TODO: Handle error
  }

  override fun onDeviceFound(device: BluetoothDevice, scanRecord: ScanRecord?) {
    synchronized(connectionMutex) {
      if (connectionState != VerifierConnectionState.NOT_CONNECTED) {
        Log.d(logTag, "Device found is ignored $device")
        return
      }
      val scanResponsePayload = scanRecord?.getServiceData(ParcelUuid(VerifierBleCommunicator.SCAN_RESPONSE_SERVICE_UUID))
      val advertisementPayload = scanRecord?.getServiceData(ParcelUuid(VerifierBleCommunicator.SERVICE_UUID))

      val matchedVerifierPK = matchingVerifierKey(
        advertisementPayload, scanResponsePayload, scanRecord?.deviceName, advPayload, advName, qrVerifierPK
      )

      if (matchedVerifierPK != null) {
        this.verifierPK = matchedVerifierPK
        Log.d(logTag, "Public Key of Verifier: ${Hex.toHexString(verifierPK)}")
        central.connect(device)
        connectionState = VerifierConnectionState.CONNECTING
      } else {
       Log.d(logTag, "AdvIdentifier($advPayload) is not matching with peripheral device adv")
      }
    }
  }

  override fun onDeviceConnected(device: BluetoothDevice) {
    synchronized(connectionMutex) {
      Log.d(logTag, "on Verifier device connected")
      connectionState = VerifierConnectionState.CONNECTED

      Log.d(logTag, "stopping scan for verifiers")
      central.stopScan()

      walletCryptoBox = WalletCryptoBoxBuilder.build(secureRandom)
      central.discoverServices()
    }
  }

  override fun onServicesDiscovered(serviceUuids: List<UUID>) {

    if (serviceUuids.contains(VerifierBleCommunicator.SERVICE_UUID)) {
      retryDiscoverServices.reset()
      Log.d(logTag, "onServicesDiscovered with services - $serviceUuids")
      central.requestMTU(mtuValues, MTU_REQUEST_RETRY_DELAY_TIME_IN_MILLIS)
    } else {
      retryServiceDiscovery()
    }
  }

  override fun onServicesDiscoveryFailed(errorCode: Int) {
    Log.d(logTag, "onServicesDiscoveryFailed retrying to find the services")
    retryServiceDiscovery()
  }

  private fun retryServiceDiscovery() {
    if (retryDiscoverServices.shouldRetry()) {
      central.discoverServicesDelayed(retryDiscoverServices.getWaitTime())
    } else {
      //TODO: Send service discovery failure to inji layer
      Log.d(logTag, "Retrying to find the services failed after multiple attempts")
      retryDiscoverServices.reset()
      throw ServiceNotFoundException("Services discovery failed even after multiple retries.")
    }
  }

  override fun onRequestMTUSuccess(mtu: Int) {
    Log.d(logTag, "onRequestMTUSuccess")
    maxDataBytes = mtu
    central.subscribe(VerifierBleCommunicator.SERVICE_UUID, GattService.DISCONNECT_CHAR_UUID)
    eventEmitter.emitEvent(ConnectedEvent())
    writeToIdentifyRequest()
  }

  override fun onRequestMTUFailure(errorCode: Int) {
    //TODO: Handle onRequest MTU failure
    throw MTUNegotiationFailedException("MTU negotiation failed even after multiple retries  with error code: $errorCode.")
  }

  override fun onReadSuccess(charUUID: UUID, value: ByteArray?) {
    Log.d(logTag, "Read from $charUUID successfully and value is $value")
  }

  override fun onReadFailure(charUUID: UUID?, err: Int) {}

  override fun onSubscriptionSuccess(charUUID: UUID) {
    // Do nothing
  }

  override fun onSubscriptionFailure(charUUID: UUID, err: Int) {
    //TODO: Close and send event to higher layer
  }

  override fun onDeviceDisconnected(isManualDisconnect: Boolean) {
    synchronized(connectionMutex) {
      connectionState = VerifierConnectionState.NOT_CONNECTED
      if (!isManualDisconnect) {
          eventEmitter.emitEvent(DisconnectedEvent())
      }
    }
  }

  override fun onWriteFailed(device: BluetoothDevice?, charUUID: UUID, err: Int) {
    Log.d(logTag, "Failed to write char: $charUUID with error code: $err")

    when (charUUID) {
      GattService.SUBMIT_RESPONSE_CHAR_UUID -> {
        transferHandler.sendMessage(ResponseChunkWriteFailureMessage(err))
      }
      GattService.TRANSFER_REPORT_REQUEST_CHAR_UUID -> {
        //TODO: implement a retry strategy similar to ios if the transfer report request write fails
        transferHandler.sendMessage(ResponseTransferFailureMessage("Failed to request report with err: $err"))
      }
    }
  }

  // TODO: move all subscriptions and unsubscriptions to one place

  override fun onWriteSuccess(device: BluetoothDevice?, charUUID: UUID) {
    Log.d(logTag, "Wrote to $charUUID successfully")
    when (charUUID) {
      GattService.IDENTIFY_REQUEST_CHAR_UUID -> {
        eventEmitter.emitEvent(SecureChannelEstablishedEvent())
      }
      GattService.RESPONSE_SIZE_CHAR_UUID -> {
        transferHandler.sendMessage(ResponseSizeWriteSuccessMessage())
      }
      GattService.SUBMIT_RESPONSE_CHAR_UUID -> {
        transferHandler.sendMessage(ResponseChunkWriteSuccessMessage())
      }
      GattService.TRANSFER_REPORT_REQUEST_CHAR_UUID -> {
        central.subscribe(VerifierBleCommunicator.SERVICE_UUID, GattService.TRANSFER_REPORT_RESPONSE_CHAR_UUID)
        central.subscribe(VerifierBleCommunicator.SERVICE_UUID, GattService.VERIFICATION_STATUS_CHAR_UUID)
      }
    }
  }

  override fun onResponseSent() {
    eventEmitter.emitEvent(DataSentEvent())
  }

  override fun onResponseSendFailure(errorMsg: String) {
    throw TransferFailedException(errorMsg)
  }

  override fun onNotificationReceived(charUUID: UUID, value: ByteArray?) {
    when (charUUID) {
      GattService.TRANSFER_REPORT_RESPONSE_CHAR_UUID -> {
        value?.let {
          transferHandler.sendMessage(HandleTransmissionReportMessage(TransferReport(it)))
        }
      }
      GattService.VERIFICATION_STATUS_CHAR_UUID -> {
        val status = value?.get(0)?.toInt()
        if (status != null && status == TransferHandler.VerificationStates.ACCEPTED.ordinal) {
          eventEmitter.emitEvent(VerificationStatusEvent(VerificationStatusEvent.VerificationStatus.ACCEPTED))
        } else {
          eventEmitter.emitEvent(VerificationStatusEvent(VerificationStatusEvent.VerificationStatus.REJECTED))
        }

        central.unsubscribe(VerifierBleCommunicator.SERVICE_UUID, charUUID)
        central.disconnectAndClose()
      }
      GattService.DISCONNECT_CHAR_UUID -> {
        val status = value?.get(0)?.toInt()

        if (status != null && status == DISCONNECT_STATUS) {
          central.unsubscribe(VerifierBleCommunicator.SERVICE_UUID, charUUID)
          central.disconnectAndClose()
        }
      }
    }
  }

  override fun onException(exception: BLEException) {
    handleException(WalletException("Exception in Wallet", exception))
  }

  override fun onClosed() {
    Log.d(logTag, "onClosed")
    central.quitHandler()
    val onClosedCallback = callbacks[CentralCallbacks.ON_DESTROY_SUCCESS_CALLBACK]

    onClosedCallback?.let {
      Log.d(logTag, "calling onDestroy callback")
      it()
      callbacks.remove(CentralCallbacks.ON_DESTROY_SUCCESS_CALLBACK)
    }
  }

  override fun onDestroy() {
    callbacks[CentralCallbacks.ON_DESTROY_SUCCESS_CALLBACK] = { eventEmitter.emitEvent(DisconnectedEvent()) }
  }

  fun setAdvPayload(advIdentifier: String, verifierPK: String) {
    this.advPayload = AdvertisementPayload.getAdvPayload(advIdentifier, verifierPK)
    this.advName = advIdentifier
    this.qrVerifierPK = Hex.decode(verifierPK)
  }

  fun sendData(payload: String) {
    val dataInBytes = payload.toByteArray()
    Log.d(logTag, "dataInBytes size: ${dataInBytes.size}")
    val compressedBytes = Util.compress(dataInBytes)
    Log.d(logTag, "compression before: ${dataInBytes.size} and after: ${compressedBytes?.size}")
    try {
      val encryptedData = secretsTranslator?.encryptToSend(compressedBytes)
      if (encryptedData != null) {
        //Log.i(logTag, "Sha256 of Encrypted Data: ${Util.getSha256(encryptedData)}")
        transferHandler.sendMessage(InitResponseTransferMessage(encryptedData, maxDataBytes))
      } else {
        Log.e(logTag, "encrypted data is null, with size: ${dataInBytes.size} and compressed size: ${compressedBytes?.size}")
      }
    } catch (e: Exception) {
      Log.e(logTag, "failed to encrypt with size: ${dataInBytes.size} and compressed size ${compressedBytes?.size}, with exception: ${e.message}, stacktrace: ${e.stackTraceToString()}")
    }
  }
}
