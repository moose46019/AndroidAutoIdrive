package me.hufman.androidautoidrive.carapp.music

import android.util.Log
import de.bmw.idrive.BMWRemoting
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.carapp.RHMIApplicationSynchronized
import me.hufman.androidautoidrive.music.MusicAppInfo
import me.hufman.androidautoidrive.music.MusicController
import me.hufman.idriveconnectionkit.android.IDriveConnectionListener
import me.hufman.idriveconnectionkit.rhmi.RHMIApplicationEtch

class AVContextHandler(val app: RHMIApplicationSynchronized, val controller: MusicController) {
	val TAG = "AVContextHandler"
	val carConnection = ((app.app) as RHMIApplicationEtch).remoteServer
	val knownApps = HashMap<MusicAppInfo, Int>()
	val appHandles = HashMap<Int, MusicAppInfo>()
	var currentContext = false
	var desiredApp: MusicAppInfo? = null    // the app that we want to play
	var desiredState: BMWRemoting.AVPlayerState? = null

	fun updateApps(apps: List<MusicAppInfo>) {
		synchronized(app) {
			for (app in knownApps.keys) {
				if (!apps.contains(app)) {
					Log.i(TAG, "Disposing avHandle for old app ${app.name}")
					val oldHandle = knownApps.remove(app)
					carConnection.av_dispose(oldHandle)
					appHandles.remove(oldHandle)
				}
			}

			for (app in apps) {
				if (!knownApps.containsKey(app)) {
					Log.i(TAG, "Creating avHandle for new app ${app.name}")
					val handle = carConnection.av_create(IDriveConnectionListener.instanceId, "androidautoidrive.${app.packageName}")
					knownApps[app] = handle
					appHandles[handle] = app
				}
			}
		}
	}

	fun av_requestContext(app: MusicAppInfo) {
			val handle = knownApps[app]
			if (handle == null) {
				Log.w(TAG, "Wanted to requestContext for missing app ${app.name}?")
				return
			}
			desiredApp = app
			val setting = AppSettings[AppSettings.KEYS.AUDIO_ENABLE_CONTEXT]
			if (setting.toBoolean()) {
				Log.i(TAG, "Sending requestContext to car for ${app.name}")
				synchronized(this.app) {
					carConnection.av_requestConnection(handle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT)
				}
				// start playback anyways
				controller.connectApp(app)
				controller.play()
				av_playerStateChanged(handle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT, BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PLAY)
			} else {
				// just assume the car has given us access, and play the app anyways
				controller.connectApp(app)
				controller.play()
				av_playerStateChanged(handle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT, BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PLAY)
			}
	}

	fun av_connectionGranted(handle: Int?, connectionType: BMWRemoting.AVConnectionType?) {
		val app = appHandles[handle]
		if (app == null) {
			Log.w(TAG, "Successful connection request for unknown app handle $handle?")
			return
		}
		val desiredState = this.desiredState
		this.desiredApp = app
		if (controller.currentApp?.musicAppInfo != app) {
			Log.i(TAG, "Car declares current audio connection to ${app.name}")
			controller.connectApp(app)
		}
		if (desiredState != null) {
			enactPlayerState(desiredState)
			av_playerStateChanged(handle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT, BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PLAY)
		}
		currentContext = true
	}

	fun av_requestPlayerState(handle: Int?, connectionType: BMWRemoting.AVConnectionType?, playerState: BMWRemoting.AVPlayerState?) {
		val app = appHandles[handle]
		Log.i(TAG, "Received requestPlayerState $playerState for ${app?.name}")
		if (playerState != null) {
			if (controller.currentApp?.musicAppInfo == app) {
				enactPlayerState(playerState)
				av_playerStateChanged(handle, connectionType, playerState)
			}
			if (desiredApp == app || desiredApp == null) {
				// car sent a command for the desired app
				desiredState = playerState
			} else if (desiredApp != null) {
				// but still tell the car that it happened
				av_playerStateChanged(handle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT, playerState)
			} else {
				Log.w(TAG, "Unknown state! desiredApp=${desiredApp?.name} connectedApp=${controller.currentApp}")
			}
		}
	}

	private fun av_playerStateChanged(handle: Int?, connectionType: BMWRemoting.AVConnectionType?, playerState: BMWRemoting.AVPlayerState?) {
		// helper function to help synchronize car accesses
		synchronized(app) {
			carConnection.av_playerStateChanged(handle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT, playerState)
		}
	}

	private fun enactPlayerState(playerState: BMWRemoting.AVPlayerState) {
		when (playerState) {
			BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PAUSE -> controller.pause()
			BMWRemoting.AVPlayerState.AV_PLAYERSTATE_STOP -> controller.pause()
			BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PLAY -> controller.play()
		}
	}

	fun av_connectionDeactivated(handle: Int?, connectionType: BMWRemoting.AVConnectionType?) {
		// the car is requesting the current app stop so that a different app can play
		// either another app within our own app (which won't trigger connectionGranted)
		// or another source entirely
		val app = appHandles[handle]
		Log.i(TAG, "Deactivating app ${app?.name}")
		controller.pause()
		if (desiredApp == app) {
			// User didn't select a different app, so the car is switching away
			Log.i(TAG, "Detecting our loss of audio focus")
			currentContext = false
		}
	}

	fun av_multimediaButtonEvent(handle: Int?, event: BMWRemoting.AVButtonEvent?) {
		when (event) {
			BMWRemoting.AVButtonEvent.AV_EVENT_SKIP_UP -> controller.skipToNext()
			BMWRemoting.AVButtonEvent.AV_EVENT_SKIP_DOWN -> controller.skipToPrevious()
		}
	}
}