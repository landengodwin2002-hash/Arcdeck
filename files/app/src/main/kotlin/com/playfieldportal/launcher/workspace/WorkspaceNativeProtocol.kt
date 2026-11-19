package com.playfieldportal.launcher.workspace

import android.content.ComponentName
import android.os.IBinder
import android.os.Parcel
import android.view.MotionEvent
import android.view.Surface
import org.json.JSONObject

internal object NativeProtocol {
    const val DESCRIPTOR = "dev.arcdeck.NativeWindows.v1"
    const val CALL = IBinder.FIRST_CALL_TRANSACTION + 20
    const val TOUCH = IBinder.FIRST_CALL_TRANSACTION + 21
    const val OPEN_FILE = IBinder.FIRST_CALL_TRANSACTION + 22
    const val DESTROY = 16777115 // Shizuku UserService lifecycle transaction, not an exported Android service.
    const val MAX_WINDOWS = 4
    fun packageName(value: String): String = value.also {
        require(it.length <= 255 && it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid app package" }
    }
    fun windowPackage(value: String, host: String): String = packageName(value).also {
        require(it != host) { "ArcDeck stays on the phone display; it cannot host itself" }
    }
    fun component(value: String, pkg: String): ComponentName = requireNotNull(ComponentName.unflattenFromString(value)).also {
        require(it.packageName == packageName(pkg) && it.className.matches(Regex("[A-Za-z0-9_.$]+"))) { "Invalid app activity" }
    }
    fun user(id: Int): Int = id.also { require(it in 0..9999) { "Invalid Android profile" } }
    fun dimensions(width: Int, height: Int, density: Int) {
        require(width in 128..2560 && height in 128..2560 && width.toLong() * height <= 4_000_000 && density in 120..480) { "Window size is outside the rendering budget" }
    }
    fun call(binder: IBinder, request: JSONObject, surface: Surface? = null): JSONObject {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(request.toString())
            data.writeInt(if (surface == null) 0 else 1); surface?.writeToParcel(data, 0)
            check(binder.transact(CALL, data, reply, 0)) { "Native window service is unavailable" }
            reply.readException(); return JSONObject(requireNotNull(reply.readString())).also { check(!it.has("error")) { it.optString("error") } }
        } finally { data.recycle(); reply.recycle() }
    }
    fun touch(binder: IBinder, display: Int, event: MotionEvent) {
        val data = Parcel.obtain()
        try { data.writeInterfaceToken(DESCRIPTOR); data.writeInt(display); event.writeToParcel(data, 0); binder.transact(TOUCH, data, null, IBinder.FLAG_ONEWAY) }
        finally { data.recycle() }
    }
    fun openFile(binder: IBinder, path: String): android.os.ParcelFileDescriptor {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(path)
            check(binder.transact(OPEN_FILE, data, reply, 0)); reply.readException()
            return android.os.ParcelFileDescriptor.CREATOR.createFromParcel(reply)
        } finally { data.recycle(); reply.recycle() }
    }
}
