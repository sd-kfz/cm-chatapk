package org.cmchat.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import org.cmchat.app.ui.theme.*

/**
 * Verify App Integrity: shows the running app's signing-certificate SHA-256
 * fingerprint plus its version/build, with a one-line plain explanation. Two
 * people running the genuine, unmodified CM-Chat see the SAME fingerprint;
 * a repackaged or tampered build signed by someone else shows a different one.
 * Everything here is read locally from the installed package — nothing leaves
 * the device.
 */
@Composable
fun IntegrityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val info = remember { AppIntegrity.collect(context) }

    Column(Modifier.fillMaxSize().background(CmBackground)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("‹ Back", color = CmBlue, fontFamily = Nunito, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { onBack() })
            Text("Verify App Integrity", color = CmText, fontFamily = Nunito, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Text(
                "This is your app's signing fingerprint. Compare it with a friend " +
                    "(read it aloud or scan): if they match, you're both running the " +
                    "genuine, unmodified CM-Chat. A different fingerprint means a " +
                    "repackaged or tampered build.",
                color = CmTextDim, fontFamily = Nunito, fontSize = 13.sp,
            )

            Field("Package", info.pkg)
            Field("Version", "${info.version} (build ${info.build})")

            Text("Signing certificate · SHA-256", color = CmBlue, fontFamily = Nunito,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SelectionContainer {
                Text(info.sha256, color = CmText, fontFamily = Nunito, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(CmCard).padding(14.dp))
            }

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CmBlue)
                .clickable { clipboard.setText(AnnotatedString(info.sha256)) }
                .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("Copy fingerprint", color = CmBackground, fontFamily = Nunito,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CmCard).padding(14.dp)) {
        Text(label, color = CmTextDim, fontFamily = Nunito, fontSize = 12.sp)
        SelectionContainer {
            Text(value, color = CmText, fontFamily = Nunito, fontSize = 14.sp)
        }
    }
}

/** Reads the installed package's version + signing-cert SHA-256, locally. */
object AppIntegrity {
    data class Info(val pkg: String, val version: String, val build: String, val sha256: String)

    fun collect(ctx: Context): Info {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        return runCatching {
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val pi = pm.getPackageInfo(pkg, flags)
            val version = pi.versionName ?: "?"
            val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.longVersionCode.toString()
            else @Suppress("DEPRECATION") pi.versionCode.toString()
            val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.signingInfo?.apkContentsSigners
            else @Suppress("DEPRECATION") pi.signatures
            Info(pkg, version, build, sha256Of(sigs))
        }.getOrElse { Info(pkg, "?", "?", "unavailable") }
    }

    private fun sha256Of(sigs: Array<Signature>?): String {
        val sig = sigs?.firstOrNull() ?: return "unavailable"
        val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
