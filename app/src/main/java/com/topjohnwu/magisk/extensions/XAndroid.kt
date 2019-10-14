package com.topjohnwu.magisk.extensions

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.*
import android.content.res.Configuration
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.topjohnwu.magisk.utils.DynamicClassLoader
import com.topjohnwu.magisk.utils.FileProvider
import com.topjohnwu.magisk.utils.Utils
import com.topjohnwu.magisk.utils.currentLocale
import java.io.File
import java.io.FileNotFoundException
import java.util.*

val packageName: String get() = get<Context>().packageName

val PackageInfo.processes
    get() = activities?.processNames.orEmpty() +
            services?.processNames.orEmpty() +
            receivers?.processNames.orEmpty() +
            providers?.processNames.orEmpty()

val Array<out ComponentInfo>.processNames get() = mapNotNull { it.processName }

val ApplicationInfo.packageInfo: PackageInfo?
    get() {
        val pm: PackageManager by inject()

        return try {
            val request = GET_ACTIVITIES or
                    GET_SERVICES or
                    GET_RECEIVERS or
                    GET_PROVIDERS
            pm.getPackageInfo(packageName, request)
        } catch (e1: Exception) {
            try {
                pm.activities(packageName).apply {
                    services = pm.services(packageName)
                    receivers = pm.receivers(packageName)
                    providers = pm.providers(packageName)
                }
            } catch (e2: Exception) {
                null
            }
        }
    }

val Uri.fileName: String
    get() {
        var name: String? = null
        get<Context>().contentResolver.query(this, null, null, null, null)?.use { c ->
            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                c.moveToFirst()
                name = c.getString(nameIndex)
            }
        }
        if (name == null && path != null) {
            val idx = path!!.lastIndexOf('/')
            name = path!!.substring(idx + 1)
        }
        return name.orEmpty()
    }

fun PackageManager.activities(packageName: String) =
    getPackageInfo(packageName, GET_ACTIVITIES)

fun PackageManager.services(packageName: String) =
    getPackageInfo(packageName, GET_SERVICES).services

fun PackageManager.receivers(packageName: String) =
    getPackageInfo(packageName, GET_RECEIVERS).receivers

fun PackageManager.providers(packageName: String) =
    getPackageInfo(packageName, GET_PROVIDERS).providers

fun Context.rawResource(id: Int) = resources.openRawResource(id)

fun Context.readUri(uri: Uri) =
    contentResolver.openInputStream(uri) ?: throw FileNotFoundException()

fun Intent.startActivity(context: Context) = context.startActivity(this)

fun Intent.toCommand(args: MutableList<String>) {
    if (action != null) {
        args.add("-a")
        args.add(action!!)
    }
    if (component != null) {
        args.add("-n")
        args.add(component!!.flattenToString())
    }
    if (data != null) {
        args.add("-d")
        args.add(dataString!!)
    }
    if (categories != null) {
        for (cat in categories) {
            args.add("-c")
            args.add(cat)
        }
    }
    if (type != null) {
        args.add("-t")
        args.add(type!!)
    }
    val extras = extras
    if (extras != null) {
        loop@ for (key in extras.keySet()) {
            val v = extras.get(key) ?: continue
            var value: Any = v
            val arg: String
            when {
                v is String -> arg = "--es"
                v is Boolean -> arg = "--ez"
                v is Int -> arg = "--ei"
                v is Long -> arg = "--el"
                v is Float -> arg = "--ef"
                v is Uri -> arg = "--eu"
                v is ComponentName -> {
                    arg = "--ecn"
                    value = v.flattenToString()
                }
                v is ArrayList<*> -> {
                    if (v.size <= 0)
                    /* Impossible to know the type due to type erasure */
                        continue@loop

                    arg = if (v[0] is Int)
                        "--eial"
                    else if (v[0] is Long)
                        "--elal"
                    else if (v[0] is Float)
                        "--efal"
                    else if (v[0] is String)
                        "--esal"
                    else
                        continue@loop  /* Unsupported */

                    val sb = StringBuilder()
                    for (o in v) {
                        sb.append(o.toString().replace(",", "\\,"))
                        sb.append(',')
                    }
                    // Remove trailing comma
                    sb.deleteCharAt(sb.length - 1)
                    value = sb
                }
                v.javaClass.isArray -> {
                    arg = if (v is IntArray)
                        "--eia"
                    else if (v is LongArray)
                        "--ela"
                    else if (v is FloatArray)
                        "--efa"
                    else if (v is Array<*> && v.isArrayOf<String>())
                        "--esa"
                    else
                        continue@loop  /* Unsupported */

                    val sb = StringBuilder()
                    val len = java.lang.reflect.Array.getLength(v)
                    for (i in 0 until len) {
                        sb.append(java.lang.reflect.Array.get(v, i)!!.toString().replace(",", "\\,"))
                        sb.append(',')
                    }
                    // Remove trailing comma
                    sb.deleteCharAt(sb.length - 1)
                    value = sb
                }
                else -> continue@loop
            }  /* Unsupported */

            args.add(arg)
            args.add(key)
            args.add(value.toString())
        }
    }
    args.add("-f")
    args.add(flags.toString())
}

fun File.provide(context: Context = get()): Uri {
    return FileProvider.getUriForFile(context, context.packageName + ".provider", this)
}

fun File.mv(destination: File) {
    inputStream().writeTo(destination)
    deleteRecursively()
}

fun String.toFile() = File(this)

fun Intent.chooser(title: String = "Pick an app") = Intent.createChooser(this, title)

fun Context.cachedFile(name: String) = File(cacheDir, name)

fun <Result> Cursor.toList(transformer: (Cursor) -> Result): List<Result> {
    val out = mutableListOf<Result>()
    while (moveToNext()) out.add(transformer(this))
    return out
}

fun ApplicationInfo.getLabel(pm: PackageManager): String {
    runCatching {
        if (labelRes > 0) {
            val res = pm.getResourcesForApplication(this)
            val config = Configuration()
            config.setLocale(currentLocale)
            res.updateConfiguration(config, res.displayMetrics)
            return res.getString(labelRes)
        }
    }

    return loadLabel(pm).toString()
}

fun Intent.exists(packageManager: PackageManager) = resolveActivity(packageManager) != null

fun Context.colorCompat(@ColorRes id: Int) = try {
    ContextCompat.getColor(this, id)
} catch (e: Resources.NotFoundException) {
    null
}

fun Context.colorStateListCompat(@ColorRes id: Int) = try {
    ContextCompat.getColorStateList(this, id)
} catch (e: Resources.NotFoundException) {
    null
}

fun Context.drawableCompat(@DrawableRes id: Int) = ContextCompat.getDrawable(this, id)
/**
 * Pass [start] and [end] dimensions, function will return left and right
 * with respect to RTL layout direction
 */
fun Context.startEndToLeftRight(start: Int, end: Int): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 &&
        resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    ) {
        return end to start
    }
    return start to end
}

fun Context.openUrl(url: String) = Utils.openLink(this, url.toUri())

@Suppress("FunctionName")
inline fun <reified T> T.DynamicClassLoader(apk: File)
        = DynamicClassLoader(apk, T::class.java.classLoader)

fun Context.unwrap() : Context {
    var context = this
    while (true) {
        if (context is ContextWrapper)
            context = context.baseContext
        else
            break
    }
    return context
}
