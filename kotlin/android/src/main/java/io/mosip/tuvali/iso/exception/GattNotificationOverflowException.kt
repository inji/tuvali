package io.mosip.tuvali.iso.exception

internal class GattNotificationOverflowException(limit: Int) :
  GattException("More than $limit notifications are waiting unread; closing the connection")
