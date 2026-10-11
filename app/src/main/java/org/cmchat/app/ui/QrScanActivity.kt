package org.cmchat.app.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView


class QrScanActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        view.viewFinder.setLaserVisibility(false)
        view.setStatusText(org.cmchat.app.i18n.Tr.s(org.cmchat.app.R.string.add_point_friend_s_cm))
        return view
    }

    override fun onStop() {
        super.onStop()


        if (!isFinishing && !isChangingConfigurations) {
            org.cmchat.app.LifecycleController.ownScreenLeft()
        }
    }
}
