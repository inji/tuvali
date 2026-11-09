package io.mosip.tuvali.iso.transport.ble

import java.util.UUID

/** Characteristics of the mdoc reader service, when the reader is the GATT server (Table 12). */
internal object MdocReaderServiceCharacteristics {
  val STATE: UUID = UUID.fromString("00000005-a123-48ce-896b-4c76973373e6")
  val CLIENT_TO_SERVER: UUID = UUID.fromString("00000006-a123-48ce-896b-4c76973373e6")
  val SERVER_TO_CLIENT: UUID = UUID.fromString("00000007-a123-48ce-896b-4c76973373e6")
  val IDENT: UUID = UUID.fromString("00000008-a123-48ce-896b-4c76973373e6")

  val ALL: List<UUID> = listOf(STATE, CLIENT_TO_SERVER, SERVER_TO_CLIENT, IDENT)
}
