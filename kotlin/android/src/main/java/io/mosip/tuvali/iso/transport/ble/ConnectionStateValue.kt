package io.mosip.tuvali.iso.transport.ble

/** Values written to or notified on the State characteristic (Table 13). */
internal object ConnectionStateValue {
  /** Written by the GATT client: ready for the transmission to start. */
  const val START: Byte = 0x01

  /** Either side: end the transaction. */
  const val END: Byte = 0x02
}
