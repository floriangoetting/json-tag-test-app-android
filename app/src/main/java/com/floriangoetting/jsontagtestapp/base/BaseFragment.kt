package com.floriangoetting.jsontagtestapp.base // Passe das Paket an deinen Projektnamen an

import androidx.fragment.app.Fragment
import com.floriangoetting.jsontagtestapp.MainActivity
import com.floriangoetting.jsontagtestapp.Tracker

open class BaseFragment : Fragment() {
    override fun onResume() {
        super.onResume()

        val fragmentName = this::class.simpleName ?: "UnknownFragment"

        val mainActivity = requireActivity() as? MainActivity

        val tracker = mainActivity?.tracker
        val fullEventData = mapOf(
            "page_title" to fragmentName
        )

        tracker?.trackEvent("screen_view", Tracker.EventType.VIEW, fullEventData)
    }
}