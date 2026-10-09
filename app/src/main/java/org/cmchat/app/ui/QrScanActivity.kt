package org.cmchat.app.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * A clean QR scanner for adding friends: portrait (declared in the manifest —
 * the library's own scanner is forced to landscape), no red laser line, a short
 * plain prompt, and no beep (ScanOptions.setBeepEnabled(false) at launch).
 * Not exported: only CM-Chat itself can open it. The camera is used only while
 * this screen is open, and nothing leaves the phone.
 */
class QrScanActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        view.viewFinder.setLaserVisibility(false)
        view.setStatusText("Point at your friend's CM-Chat QR")
        return view
    }

    override fun onStop() {
        super.onStop()
        // Left for Home / another app (not finishing back into CM-Chat): lock
        // now instead of keeping the app unlocked behind the scanner.
        if (!isFinishing && !isChangingConfigurations) {
            org.cmchat.app.LifecycleController.ownScreenLeft()
        }
    }
}
