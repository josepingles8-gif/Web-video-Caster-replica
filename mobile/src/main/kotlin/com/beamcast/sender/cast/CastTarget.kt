package com.beamcast.sender.cast

import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.sender.cast.chromecast.ChromecastTarget
import com.beamcast.sender.cast.dlna.DlnaTarget

/** One implementation per [DeviceType]; the rest of the app only talks to this. */
interface CastTarget {
    suspend fun load(device: Device, req: LoadRequest): PlaybackStatus
    suspend fun control(device: Device, action: String, value: Long? = null): PlaybackStatus
    suspend fun status(device: Device): PlaybackStatus
}

object BeamTarget : CastTarget {
    override suspend fun load(device: Device, req: LoadRequest) = CastClient.load(device, req)
    override suspend fun control(device: Device, action: String, value: Long?) = CastClient.control(device, action, value)
    override suspend fun status(device: Device) = CastClient.status(device)
}

object CastTargets {
    fun forDevice(d: Device): CastTarget = when (d.type) {
        DeviceType.BEAM -> BeamTarget
        DeviceType.DLNA -> DlnaTarget
        DeviceType.CHROMECAST -> ChromecastTarget
    }
}
